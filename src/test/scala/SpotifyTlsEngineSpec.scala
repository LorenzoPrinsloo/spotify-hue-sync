import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import ch.qos.logback.classic.Level
import client.{HttpClients, SpotifyClient}
import domain.config.*
import domain.hue.{HueCredentials, MusicArea, Psk, Zone}
import domain.spotify.{SpotifyCredentials, SpotifySession}
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import testkit.Tls

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Random

class SpotifyTlsEngineSpec extends CatsEffectSuite {

  private val zone = Zone("00000000-0000-0000-0000-000000000000", List(0, 1, 2))

  private val lights: Lights[IO] = new Lights[IO] {
    def areas: IO[List[MusicArea]] = IO.pure(Nil)
    def zone(area: String): IO[Option[Zone]] = IO.pure(None)
  }

  private val config = AppConfig(
    SpotifyCredentials("id", "secret", "refresh"),
    HueConfig(HueCredentials("10.0.0.2", "user", Psk.parse("00112233445566778899aabbccddeeff").toOption.get), Some(zone.id)),
    pollingIntervalMs = 100
  )

  test("the engine keeps polling and logs the certificate failure when Spotify's certificate is untrusted") {
    val spotify = Tls.server(HttpRoutes.of[IO] { case GET -> Root / "v1" / "me" / "player" => Ok("{}") }.orNotFound)
    (spotify, Tls.capturedLogs, HttpClients.resource[IO]).tupled.use { case ((port, seen), appender, clients) =>
      for {
        polls <- Ref.of[IO, Int](0)
        counting = Client[IO] { req =>
          Resource.eval(polls.update(_ + 1)) *> Tls.redirectTo(port)(clients.spotify).run(req)
        }
        session <- Ref.of[IO, Option[SpotifySession]](Some(SpotifySession("token", "refresh")))
        code <- Deferred[IO, String]
        client = new SpotifyClient[IO](counting, SpotifyCredentials("id", "secret", "refresh"), session, code)
        live <- settings.LiveSettings.make[IO](config.lightShow)
        engine <- SyncEngine.make[IO](config, live, client, zone, lights, new Random(1L))
        enough = IO.sleep(20.millis).productR(polls.get).iterateUntil(_ >= 3).void
        frames <- engine.run.interruptWhen(enough.attempt).compile.toList.timeout(30.seconds)
        attempted <- polls.get
        requests <- seen
        errors = Tls.logged(appender)
          .filter(e => e.getLevel == Level.ERROR && e.getLoggerName.contains("SyncEngine"))
          .map(_.getFormattedMessage)
      } yield {
        assert(attempted > 1, attempted.toString)
        assertEquals(requests.size, 0)
        assert(frames.nonEmpty)
        assert(
          errors.exists(m => m.contains("Spotify Poll Failed") && List("SSLHandshakeException", "PKIX", "certificate").exists(m.contains)),
          errors.toString
        )
      }
    }
  }
}
