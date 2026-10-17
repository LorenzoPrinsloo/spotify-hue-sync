package client

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import domain.spotify.types.TrackId
import domain.spotify.{SpotifyCredentials, SpotifySession}
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.Authorization
import testkit.Tls

import scala.concurrent.duration.*

class SpotifyTlsSpec extends CatsEffectSuite {

  private val playing =
    """{ "progress_ms": 0, "is_playing": true, "item": { "id": "track-1", "album": { "images": [ { "url": "cover" } ] } } }"""

  private val token =
    """{ "access_token": "fresh", "token_type": "Bearer", "expires_in": 3600, "scope": "user-read-playback-state", "refresh_token": "refresh-2" }"""

  private val fresh = Authorization(Credentials.Token(AuthScheme.Bearer, "fresh"))

  private def assertTlsFailure[A](result: Either[Throwable, A]): Unit =
    assert(result.left.exists(Tls.isTlsFailure), result.toString)

  private def assertNames[A](result: Either[Throwable, A], call: String): Unit =
    assert(result.left.exists({ case SpotifyTlsFailure(`call`, cause) => Tls.isTlsFailure(cause); case _ => false }), result.toString)

  private def fake(code: Option[Deferred[IO, String]] = None, firstTokenRejected: Boolean = false): Resource[IO, (Int, IO[List[Request[IO]]])] =
    Resource.eval(Ref.of[IO, Int](0)).flatMap { playerCalls =>
      Tls.server(HttpRoutes.of[IO] {
        case GET -> Root / "authorize" =>
          code.traverse_(_.complete("the-code")) *> Ok("login")
        case POST -> Root / "api" / "token" => Ok(token)
        case req @ GET -> Root / "v1" / "me" / "player" =>
          playerCalls.updateAndGet(_ + 1).flatMap { n =>
            if (req.headers.get[Authorization].contains(fresh) || (!firstTokenRejected && n > 0)) Ok(playing)
            else IO.pure(Response[IO](Status.Unauthorized))
          }
        case req @ GET -> Root / "v1" / "audio-features" / "track-1" =>
          if (req.headers.get[Authorization].contains(fresh)) Ok("""{ "tempo": 123.5 }""") else IO.pure(Response[IO](Status.Unauthorized))
      }.orNotFound)
    }

  private def spotify(client: Client[IO], session: Option[SpotifySession], code: Deferred[IO, String]): IO[SpotifyClient[IO]] =
    Ref.of[IO, Option[SpotifySession]](session).map { ref =>
      new SpotifyClient[IO](client, SpotifyCredentials("id", "secret", Some("refresh")), ref, code)
    }

  private def untrusted[A](port: Int, session: Option[SpotifySession])(use: SpotifyClient[IO] => IO[A]): IO[Either[Throwable, A]] =
    HttpClients.resource[IO].use { clients =>
      Deferred[IO, String].flatMap(code => spotify(Tls.redirectTo(port)(clients.spotify), session, code)).flatMap(use).attempt
    }.timeout(10.seconds)

  private def trusted[A](port: Int, session: Option[SpotifySession], code: Deferred[IO, String])(use: SpotifyClient[IO] => IO[A]): IO[A] =
    Tls.selfSigned.flatMap { tls =>
      HttpClients.trusting(tls).use(clients => spotify(Tls.redirectTo(port)(clients.spotify), session, code).flatMap(use))
    }.timeout(10.seconds)

  private val signedIn = Some(SpotifySession("fresh", "refresh"))

  test("the playback poll fails and sends no bearer token to an untrusted certificate") {
    fake().use { (port, seen) =>
      untrusted(port, signedIn)(_.getPlaybackState).flatMap(r =>
        seen.map(s => (assertTlsFailure(r), assertNames(r, "Playback Poll"), assertEquals(s.size, 0)))
      )
    }
  }

  test("the tempo request fails and sends no bearer token to an untrusted certificate") {
    fake().use { (port, seen) =>
      untrusted(port, signedIn)(_.getTempo(TrackId("track-1"))).flatMap(r =>
        seen.map(s => (assertTlsFailure(r), assertNames(r, "Tempo Lookup"), assertEquals(s.size, 0)))
      )
    }
  }

  test("signing in against an untrusted certificate sends neither the cookie nor the client secret") {
    fake().use { (port, seen) =>
      untrusted(port, None)(_.getPlaybackState).flatMap(r =>
        seen.map(s => (assertTlsFailure(r), assertNames(r, "Authorize"), assertEquals(s.size, 0)))
      )
    }
  }

  test("renewing against an untrusted certificate sends no client secret") {
    fake().use { (port, seen) =>
      untrusted(port, signedIn)(_.renew).flatMap(r => seen.map(s => (assertTlsFailure(r), assertNames(r, "Renew"), assertEquals(s.size, 0))))
    }
  }

  test("the code exchange fails and sends no client secret to an untrusted certificate") {
    fake().use { (port, seen) =>
      HttpClients.resource[IO].use { clients =>
        val authorizeAnswered = Client[IO] { req =>
          if (req.uri.path.renderString == "/authorize") Resource.pure(Response[IO](Status.Ok))
          else Tls.redirectTo(port)(clients.spotify).run(req)
        }
        for {
          code <- Deferred[IO, String]
          _ <- code.complete("the-code")
          client <- spotify(authorizeAnswered, None, code)
          r <- client.getPlaybackState.attempt
          s <- seen
        } yield (assertTlsFailure(r), assertNames(r, "Code Exchange"), assertEquals(s.size, 0))
      }.timeout(10.seconds)
    }
  }

  test("signing in over a trusted certificate exchanges the code and polls with the new token") {
    Deferred[IO, String].flatMap { code =>
      fake(Some(code)).use { (port, seen) =>
        for {
          state <- trusted(port, None, code)(_.getPlaybackState)
          requests <- seen
          form <- requests.find(_.method == Method.POST).traverse(_.as[UrlForm])
          player = requests.filter(_.uri.path.renderString == "/v1/me/player")
        } yield {
          assert(state.exists(_.isPlaying), state.toString)
          assertEquals(form.map(_.getFirstOrElse("grant_type", "")), Some("authorization_code"))
          assertEquals(form.map(_.getFirstOrElse("code", "")), Some("the-code"))
          assertEquals(player.flatMap(_.headers.get[Authorization]), List(fresh))
        }
      }
    }
  }

  test("a rejected token over TLS is renewed once, then the poll and the tempo succeed") {
    Deferred[IO, String].flatMap { code =>
      fake(firstTokenRejected = true).use { (port, seen) =>
        trusted(port, Some(SpotifySession("expired", "refresh-1")), code) { client =>
          (client.getPlaybackState, client.getTempo(TrackId("track-1"))).tupled
        }.flatMap { (state, tempo) =>
          seen.map { requests =>
            assert(state.exists(_.isPlaying), state.toString)
            assertEquals(tempo, Some(123.5))
            assertEquals(requests.count(_.method == Method.POST), 1)
          }
        }
      }
    }
  }
}
