package client

import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import domain.spotify.{PlaybackState, SpotifyCredentials, SpotifySession}
import fs2.io.file.{Files, Path}
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.scalacheck.Gen
import testkit.Tls

import scala.concurrent.duration.*

class SpotifyTokenFileSpec extends CatsEffectSuite {

  private case class TokenCall(grant: String, refreshToken: String, code: String)

  private case class Fake(app: HttpApp[IO], calls: IO[List[TokenCall]])

  private case class Started(client: SpotifyClient[IO], session: Ref[IO, Option[SpotifySession]], callback: HttpApp[IO])

  private val playing =
    """{ "progress_ms": 0, "is_playing": true, "item": { "id": "track-1", "album": { "images": [ { "url": "cover" } ] } } }"""

  private def tokens(refresh: Option[String]): IO[Response[IO]] =
    Ok(
      s"""{ "access_token": "signed-in", "token_type": "Bearer", "expires_in": 3600, "scope": "user-read-playback-state"${refresh.fold("")(r => s""", "refresh_token": "$r"""")} }"""
    )

  private val invalidGrant: IO[Response[IO]] = BadRequest("""{ "error": "invalid_grant" }""")

  private def fake(issued: String)(answer: String => IO[Response[IO]]): IO[Fake] =
    Ref.of[IO, List[TokenCall]](Nil).map { calls =>
      val app = HttpRoutes.of[IO] {
        case req @ POST -> Root / "api" / "token" =>
          req.as[UrlForm].flatMap { form =>
            val call = TokenCall(form.getFirstOrElse("grant_type", ""), form.getFirstOrElse("refresh_token", ""), form.getFirstOrElse("code", ""))
            calls.update(_ :+ call) *> (if (call.grant == "authorization_code") tokens(Some(issued)) else answer(call.refreshToken))
          }
        case req @ GET -> Root / "v1" / "me" / "player" =>
          if (req.headers.get[Authorization].contains(Authorization(Credentials.Token(AuthScheme.Bearer, "signed-in")))) Ok(playing)
          else IO.pure(Response[IO](Status.Unauthorized))
      }.orNotFound
      Fake(app, calls.get)
    }

  private def answering(known: Map[String, Option[String]]): String => IO[Response[IO]] =
    token => known.get(token).fold(invalidGrant)(tokens)

  private def start(path: Path, spotify: Fake, configured: Option[String]): IO[Started] =
    for {
      store <- TokenStore.file(path, FileModes.posix[IO])
      codes <- SignInCodes.make[IO]
      session <- Ref.of[IO, Option[SpotifySession]](None)
      creds = SpotifyCredentials("id", "secret", configured)
    } yield Started(
      new SpotifyClient[IO](Client.fromHttpApp(spotify.app), creds, session, codes, store),
      session,
      SpotifyClient.callbackRoutes[IO](codes).orNotFound
    )

  private def browserPoll(s: Started): IO[Option[PlaybackState]] =
    TestControl.executeEmbed(for {
      poll <- s.client.getPlaybackState.start
      _ <- IO.sleep(1.second)
      _ <- s.callback.run(Request(Method.GET, uri"/callback?code=browser-code"))
      state <- poll.joinWithNever
    } yield state)

  private def inTemp(use: Path => IO[Unit]): IO[Unit] = Files[IO].tempDirectory.use(dir => use(dir / "state" / "refresh-token"))

  private def seed(path: Path, token: String): IO[Unit] =
    TokenStore.file(path, FileModes.posix[IO]).flatMap(_.save(token))

  private def contents(path: Path): IO[String] = Files[IO].readUtf8(path).compile.string

  private def logged(appender: ListAppender[ILoggingEvent], name: String, level: Level): List[String] =
    Tls.logged(appender).filter(e => e.getLoggerName == name && e.getLevel == level).map(_.getFormattedMessage)

  private def visits(appender: ListAppender[ILoggingEvent]): Int =
    logged(appender, "client.SpotifyClient", Level.INFO).count(_.startsWith("Please visit"))

  private def withLogs[A](body: ListAppender[ILoggingEvent] => IO[A]): IO[A] =
    (Tls.capturedLogs, Tls.loggingAt("client", Level.TRACE)).tupled.use((appender, _) => body(appender))

  private def assertPlaying(state: Option[PlaybackState]): Unit = assert(state.exists(_.isPlaying), state.toString)

  private def browserThenRestart(t: String): IO[Unit] =
    withLogs { logs =>
      inTemp { path =>
        for {
          first <- fake(s"$t-issued")(answering(Map.empty))
          s1 <- start(path, first, None)
          state <- browserPoll(s1)
          _ = assertPlaying(state)
          _ <- contents(path).assertEquals(s"$t-issued\n")
          second <- fake(s"$t-other")(answering(Map(s"$t-issued" -> None)))
          s2 <- start(path, second, None)
          again <- s2.client.getPlaybackState
          _ = assertPlaying(again)
          _ <- second.calls.assertEquals(List(TokenCall("refresh_token", s"$t-issued", "")))
          _ = assertEquals(visits(logs), 1)
        } yield ()
      }
    }

  private def renewalIsSaved(t: String): IO[Unit] =
    withLogs { _ =>
      inTemp { path =>
        for {
          first <- fake(s"$t-unused")(answering(Map(s"$t-conf" -> Some(s"$t-1"), s"$t-1" -> Some(s"$t-2"))))
          s1 <- start(path, first, Some(s"$t-conf"))
          _ <- s1.client.getPlaybackState
          _ <- s1.client.renew
          _ <- contents(path).assertEquals(s"$t-2\n")
          second <- fake(s"$t-unused")(answering(Map(s"$t-2" -> None)))
          s2 <- start(path, second, None)
          _ <- s2.client.getPlaybackState
          _ <- second.calls.assertEquals(List(TokenCall("refresh_token", s"$t-2", "")))
        } yield ()
      }
    }

  private def savedBeatsConfigured(t: String): IO[Unit] =
    withLogs { _ =>
      inTemp { path =>
        for {
          _ <- seed(path, s"$t-saved")
          known = Map[String, Option[String]](s"$t-saved" -> None, s"$t-conf" -> None)
          viaPoll <- fake(s"$t-unused")(answering(known))
          s1 <- start(path, viaPoll, Some(s"$t-conf"))
          _ <- s1.client.getPlaybackState
          _ <- viaPoll.calls.assertEquals(List(TokenCall("refresh_token", s"$t-saved", "")))
          viaRenew <- fake(s"$t-unused")(answering(known))
          s2 <- start(path, viaRenew, Some(s"$t-conf"))
          _ <- s2.client.renew
          _ <- viaRenew.calls.assertEquals(List(TokenCall("refresh_token", s"$t-saved", "")))
        } yield ()
      }
    }

  private def rejectedFallsBackToConfigured(t: String): IO[Unit] =
    withLogs { logs =>
      inTemp { path =>
        for {
          _ <- seed(path, s"$t-rejected")
          first <- fake(s"$t-unused")(answering(Map(s"$t-conf" -> None)))
          s1 <- start(path, first, Some(s"$t-conf"))
          state <- s1.client.getPlaybackState
          _ = assertPlaying(state)
          _ <- first.calls.assertEquals(List(TokenCall("refresh_token", s"$t-rejected", ""), TokenCall("refresh_token", s"$t-conf", "")))
          _ = assertEquals(
            logged(logs, "client.SpotifyClient", Level.WARN).count(_.startsWith("Spotify rejected the saved refresh token")),
            1
          )
          _ <- Files[IO].exists(path).assertEquals(false)
          third <- fake(s"$t-unused")(answering(Map(s"$t-conf" -> None)))
          s3 <- start(path, third, Some(s"$t-conf"))
          _ <- s3.client.getPlaybackState
          _ <- third.calls.assertEquals(List(TokenCall("refresh_token", s"$t-conf", "")))
        } yield ()
      }
    }

  private def rejectedFallsBackToBrowser(t: String): IO[Unit] =
    withLogs { _ =>
      inTemp { path =>
        for {
          _ <- seed(path, s"$t-rejected")
          first <- fake(s"$t-issued")(answering(Map.empty))
          s1 <- start(path, first, None)
          state <- browserPoll(s1)
          _ = assertPlaying(state)
          _ <- first.calls.assertEquals(List(TokenCall("refresh_token", s"$t-rejected", ""), TokenCall("authorization_code", "", "browser-code")))
          third <- fake(s"$t-unused")(answering(Map(s"$t-issued" -> None)))
          s3 <- start(path, third, None)
          _ <- s3.client.getPlaybackState
          _ <- third.calls.assertEquals(List(TokenCall("refresh_token", s"$t-issued", "")))
        } yield ()
      }
    }

  private def malformedThenUnavailable(t: String): IO[Unit] =
    withLogs { logs =>
      inTemp { path =>
        for {
          _ <- Files[IO].createDirectories(path.parent.get)
          _ <- fs2.Stream.emit(s"$t has spaces").through(Files[IO].writeUtf8(path)).compile.drain
          unavailable <- fake(s"$t-unused")(_ => ServiceUnavailable("down"))
          s <- start(path, unavailable, Some(s"$t-conf"))
          polls <- s.client.getPlaybackState.attempt.replicateA(2)
          _ = assert(polls.forall { case Left(_: SpotifyTokenError.Failed) => true; case _ => false }, polls.toString)
          _ = assertEquals(logged(logs, "client.TokenStore", Level.WARN).count(_.contains("malformed")), 1)
        } yield ()
      }
    }

  private def unwritableParent(t: String): IO[Unit] =
    withLogs { logs =>
      Files[IO].tempDirectory.use { dir =>
        val blocker = dir / "blocker"
        for {
          _ <- fs2.Stream.emit("file").through(Files[IO].writeUtf8(blocker)).compile.drain
          answers = (1 to 4).map(n => s"$t-$n" -> Some(s"$t-${n + 1}")).toMap + (s"$t-conf" -> Some(s"$t-1"))
          spotify <- fake(s"$t-unused")(answering(answers))
          s <- start(blocker / "refresh-token", spotify, Some(s"$t-conf"))
          _ <- s.client.getPlaybackState
          _ <- s.client.renew.replicateA_(3)
          again <- s.client.getPlaybackState
          _ = assertPlaying(again)
          _ = assertEquals(logged(logs, "client.TokenStore", Level.WARN).count(_.startsWith("Couldn't save")), 1)
        } yield ()
      }
    }

  private val scenarios: List[(String, String => IO[Unit])] = List(
    "a browser sign-in is remembered, so the next start doesn't open the browser" -> browserThenRestart,
    "a renewed refresh token is saved and used by the next start" -> renewalIsSaved,
    "a saved token beats a configured one, on the first poll and on the first renewal" -> savedBeatsConfigured,
    "a rejected saved token is deleted and the configured token is used" -> rejectedFallsBackToConfigured,
    "a rejected saved token is deleted and the browser sign-in follows when nothing is configured" -> rejectedFallsBackToBrowser,
    "a malformed file warns once, and an unavailable Spotify fails the polls without crashing" -> malformedThenUnavailable,
    "a token path that can't be written warns once while renewals keep working" -> unwritableParent
  )

  scenarios.foreach((name, scenario) => test(name)(scenario("tok")))

  private val tokenGen: Gen[String] = Gen.listOfN(24, Gen.alphaNumChar).map(_.mkString)

  test("no log event from the client or the token store contains a token") {
    val generated = List.fill(3)(tokenGen.sample).flatten
    assert(generated.nonEmpty)
    generated.traverse_ { t =>
      scenarios.traverse_ { (_, scenario) =>
        (Tls.capturedLogs, Tls.loggingAt("client", Level.TRACE)).tupled.use { (appender, _) =>
          scenario(t) *> IO {
            Tls.logged(appender).foreach { e =>
              val texts = e.getFormattedMessage :: e.getMDCPropertyMap.values().toArray.toList.map(_.toString) ++
                Option(e.getThrowableProxy).map(_.getMessage).toList
              assert(!texts.exists(_.contains(t)), texts.toString)
            }
          }
        }
      }
    }
  }
}
