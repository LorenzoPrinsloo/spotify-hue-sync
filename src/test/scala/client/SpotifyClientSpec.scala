package client

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import domain.spotify.{SpotifyCredentials, SpotifySession}
import domain.spotify.types.TrackId
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*

class SpotifyClientSpec extends CatsEffectSuite {

  private def spotify(response: IO[Response[IO]]): IO[SpotifyClient[IO]] =
    for {
      session <- Ref.of[IO, Option[SpotifySession]](Some(SpotifySession("token", "refresh")))
      code <- Deferred[IO, String]
      app = HttpRoutes.of[IO] { case GET -> Root / "v1" / "audio-features" / "track-1" => response }.orNotFound
    } yield new SpotifyClient[IO](Client.fromHttpApp(app), SpotifyCredentials("id", "secret", "refresh"), session, code)

  test("reads the tempo from Spotify's audio features") {
    spotify(Ok("""{ "id": "track-1", "tempo": 123.5, "energy": 0.8 }"""))
      .flatMap(_.getTempo(TrackId("track-1")))
      .assertEquals(Some(123.5))
  }

  test("no tempo when Spotify refuses the request") {
    spotify(Forbidden("""{ "error": { "status": 403 } }""")).flatMap(_.getTempo(TrackId("track-1"))).assertEquals(None)
  }

  test("no tempo when the track has none") {
    spotify(Ok("""{ "id": "track-1", "tempo": 0 }""")).flatMap(_.getTempo(TrackId("track-1"))).assertEquals(None)
  }

  private val playing =
    """{ "progress_ms": 0, "is_playing": true, "item": { "id": "track-1", "album": { "images": [ { "url": "cover" } ] } } }"""

  private val rejected: IO[Response[IO]] = IO.pure(Response[IO](Status.Unauthorized))

  private case class Expiring(client: SpotifyClient[IO], session: Ref[IO, Option[SpotifySession]], renewals: Ref[IO, List[String]])

  /**
   * A Spotify that only accepts the access token "fresh", starting from a session whose access token has expired.
   * The token endpoint answers with `renewal` and records the refresh tokens it was sent.
   */
  private def expiring(renewal: IO[Response[IO]]): IO[Expiring] =
    for {
      session <- Ref.of[IO, Option[SpotifySession]](Some(SpotifySession("expired", "refresh-1")))
      renewals <- Ref.of[IO, List[String]](Nil)
      code <- Deferred[IO, String]
      fresh = Authorization(Credentials.Token(AuthScheme.Bearer, "fresh"))
      app = HttpRoutes.of[IO] {
        case req @ POST -> Root / "api" / "token" =>
          req.as[UrlForm].flatMap(form => renewals.update(_ :+ form.getFirstOrElse("refresh_token", ""))) *> renewal
        case req @ GET -> Root / "v1" / "me" / "player" =>
          if (req.headers.get[Authorization].contains(fresh)) Ok(playing) else rejected
        case req @ GET -> Root / "v1" / "audio-features" / "track-1" =>
          if (req.headers.get[Authorization].contains(fresh)) Ok("""{ "tempo": 123.5 }""") else rejected
      }.orNotFound
    } yield Expiring(new SpotifyClient[IO](Client.fromHttpApp(app), SpotifyCredentials("id", "secret", "refresh-0"), session, code), session,
      renewals)

  private def renewedTo(access: String, refresh: Option[String]): IO[Response[IO]] =
    Ok(
      s"""{ "access_token": "$access", "token_type": "Bearer", "expires_in": 3600, "scope": "user-read-playback-state"${refresh.fold("")(r => s""", "refresh_token": "$r"""")} }"""
    )

  private def renewed(refresh: Option[String]): IO[Response[IO]] = renewedTo("fresh", refresh)

  test("when the access token has expired, renews it with the refresh token, once, and carries on") {
    for {
      spotify <- expiring(renewed(None))
      polls <- spotify.client.getPlaybackState.replicateA(3)
      _ = assert(polls.forall(_.exists(_.isPlaying)), polls)
      _ <- spotify.renewals.get.assertEquals(List("refresh-1"))
    } yield ()
  }

  test("tempo lookups renew an expired access token too") {
    expiring(renewed(None)).flatMap(_.client.getTempo(TrackId("track-1"))).assertEquals(Some(123.5))
  }

  test("keeps the refresh token Spotify sends back when renewing, or the old one if it sends none") {
    List(None -> "refresh-1", Some("refresh-2") -> "refresh-2").traverse_ { (returned, kept) =>
      for {
        spotify <- expiring(renewed(returned))
        _ <- spotify.client.getPlaybackState
        _ <- spotify.session.get.assertEquals(Some(SpotifySession("fresh", kept)))
      } yield ()
    }
  }

  test("fails, instead of reporting that nothing is playing, when the access token can't be renewed") {
    expiring(BadRequest("""{ "error": "invalid_grant" }""")).flatMap(_.client.getPlaybackState).intercept[RuntimeException]
  }

  test("signs in on first use: swaps the code from the callback for tokens, then uses them") {
    for {
      session <- Ref.of[IO, Option[SpotifySession]](None)
      code <- Deferred[IO, String]
      exchanged <- Ref.of[IO, Option[UrlForm]](None)
      signedIn = Authorization(Credentials.Token(AuthScheme.Bearer, "signed-in"))
      app = HttpRoutes.of[IO] {
        case GET -> Root / "authorize" => code.complete("code-1") *> Ok("signed in")
        case req @ POST -> Root / "api" / "token" =>
          req.as[UrlForm].flatMap(form => exchanged.set(Some(form))) *> renewedTo("signed-in", Some("refresh-9"))
        case req @ GET -> Root / "v1" / "me" / "player" =>
          if (req.headers.get[Authorization].contains(signedIn)) Ok(playing) else rejected
      }.orNotFound
      state <- new SpotifyClient[IO](Client.fromHttpApp(app), SpotifyCredentials("id", "secret", "refresh-0"), session, code).getPlaybackState
      form <- exchanged.get
      stored <- session.get
    } yield {
      assert(state.exists(_.isPlaying), state.toString)
      assertEquals(form.flatMap(_.getFirst("grant_type")), Some("authorization_code"))
      assertEquals(form.flatMap(_.getFirst("code")), Some("code-1"))
      assertEquals(stored, Some(SpotifySession("signed-in", "refresh-9")))
    }
  }

  test("sign-in fails, rather than carrying on without a token, when Spotify won't swap the code") {
    for {
      session <- Ref.of[IO, Option[SpotifySession]](None)
      code <- Deferred[IO, String]
      app = HttpRoutes.of[IO] {
        case GET -> Root / "authorize" => code.complete("code-1") *> Ok("signed in")
        case POST -> Root / "api" / "token" => BadRequest("""{ "error": "invalid_grant" }""")
      }.orNotFound
      _ <- new SpotifyClient[IO](Client.fromHttpApp(app), SpotifyCredentials("id", "secret", "refresh-0"), session, code)
        .getPlaybackState.intercept[RuntimeException]
      stored <- session.get
    } yield assertEquals(stored, None)
  }

  test("the callback hands the sign-in code over, and nothing else counts as a callback") {
    for {
      code <- Deferred[IO, String]
      routes = SpotifyClient.callbackRoutes[IO](SignInCodes.once(code)).orNotFound
      answered <- routes.run(Request(Method.GET, uri"/callback?code=abc&state=xyz"))
      handed <- code.get
      without <- routes.run(Request(Method.GET, uri"/callback"))
    } yield {
      assertEquals((answered.status, handed), (Status.Ok, "abc"))
      assertEquals(without.status, Status.NotFound)
    }
  }
}
