package client

import cats.effect.{Deferred, IO, Ref}
import domain.spotify.SpotifyCredentials
import domain.spotify.types.TrackId
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*

class SpotifyClientSpec extends CatsEffectSuite {

  private def spotify(response: IO[Response[IO]]): IO[SpotifyClient[IO]] =
    for {
      token <- Ref.of[IO, Option[String]](Some("token"))
      code <- Deferred[IO, String]
      app = HttpRoutes.of[IO] { case GET -> Root / "v1" / "audio-features" / "track-1" => response }.orNotFound
    } yield new SpotifyClient[IO](Client.fromHttpApp(app), SpotifyCredentials("id", "secret", "refresh"), token, code)

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
}
