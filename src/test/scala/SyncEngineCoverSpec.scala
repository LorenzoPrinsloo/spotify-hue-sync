import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import client.{AlbumCovers, SpotifyClient}
import domain.config.*
import domain.engine.{Hsb, Palette, PaletteType, RGB}
import domain.hue.{Frame, HueCredentials, MusicArea, Psk, Zone}
import domain.spotify.{SpotifyCredentials, SpotifySession}
import domain.spotify.types.TrackId
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import scala.concurrent.duration.*
import scala.util.Random
import settings.{LiveSettings, SettingsRoutes, ShowControls}
import testkit.{Covers, Tls}
import testkit.Colours.{brightness, hue, hueDistance, saturation}

import java.net.ConnectException

/** Runs the engine against a fake Spotify and a fake cover server on a simulated clock. */
class SyncEngineCoverSpec extends CatsEffectSuite {

  private val zone = Zone("00000000-0000-0000-0000-000000000000", List(0, 1, 2, 4, 7))
  private val track1 = TrackId("track-1")
  private val track2 = TrackId("track-2")
  private val resting = RGB.safe(30, 20, 8).get
  private val red = RGB.safe(220, 20, 20).get
  private val blue = RGB.safe(20, 20, 220).get
  private val grey = RGB.safe(128, 128, 128).get
  private val triadic = LightShowConfig(palette = PaletteType.Triadic)
  private def cover(name: String): Option[String] = Some(s"https://covers.test/$name")

  private def config(show: LightShowConfig): AppConfig =
    AppConfig(
      SpotifyCredentials("id", "secret", Some("refresh")),
      HueConfig(HueCredentials("10.0.0.2", "user", Psk.parse("00112233445566778899aabbccddeeff").toOption.get), Some(zone.id)),
      pollingIntervalMs = 1000,
      lightShow = show
    )

  private val lights: Lights[IO] = new Lights[IO] {
    def areas: IO[List[MusicArea]] = IO.pure(Nil)
    def zone(area: String): IO[Option[Zone]] = IO.pure(None)
  }

  private case class Setup(
    show: LightShowConfig = triadic,
    seed: Long = 1L,
    coverOf: String => Option[String] = _ => cover("red"),
    trackAt: FiniteDuration => String = _ => "track-1",
    wrap: AlbumCovers[IO] => AlbumCovers[IO] = identity
  )

  private case class Env(
    engine: SyncEngine[IO],
    live: LiveSettings[IO],
    requests: Ref[IO, Int],
    logs: ListAppender[ILoggingEvent],
    frames: Ref[IO, List[(FiniteDuration, Frame)]]
  ) {
    def warnings(trackId: TrackId): List[ILoggingEvent] =
      Tls.logged(logs).filter { e =>
        e.getLevel == Level.WARN && e.getLoggerName.endsWith("SyncEngine") && e.getFormattedMessage.contains(trackId.toString)
      }
  }

  private def coverColour(hueDegrees: Double, saturation: Double, brightness: Double): RGB = {
    val (r, g, b) = Hsb.toRgb((hueDegrees / 360.0).toFloat, saturation.toFloat, brightness.toFloat)
    RGB.safe(r, g, b).get
  }

  private def png(name: String): IO[Response[IO]] = name match {
    case "red" => Ok(Covers.png(red -> 1.0))
    case "blue" => Ok(Covers.png(blue -> 1.0))
    case "pastel" => Ok(Covers.png(coverColour(200.0, 0.45, 0.95) -> 1.0))
    case "dull" => Ok(Covers.png(coverColour(30.0, 0.8, 0.35) -> 1.0))
    case "grey" => Ok(Covers.png(grey -> 97.0, red -> 3.0))
    case "error" => InternalServerError("no")
    case "garbage" => Ok("not an image")
    case "refused" => IO.raiseError(new ConnectException("refused"))
    case "slow" => IO.sleep(2.seconds) *> Ok(Covers.png(red -> 1.0))
    case _ => NotFound()
  }

  private def playing(trackId: String, url: Option[String]): String = {
    val images = url.fold("[]")(u => s"""[ { "url": "$u" } ]""")
    s"""{ "progress_ms": 0, "is_playing": true, "item": { "id": "$trackId", "album": { "images": $images } } }"""
  }

  private def world[A](setup: Setup, duration: FiniteDuration = 6.seconds)(body: Env => IO[A]): IO[A] =
    (Tls.capturedLogs, Tls.loggingAt("<empty>.SyncEngine", Level.INFO)).tupled.use { (logs, _) =>
      TestControl.executeEmbed {
        for {
          session <- Ref.of[IO, Option[SpotifySession]](Some(SpotifySession("token", "refresh")))
          code <- Deferred[IO, String]
          requests <- Ref.of[IO, Int](0)
          frames <- Ref.of[IO, List[(FiniteDuration, Frame)]](Nil)
          spotifyApp = HttpRoutes.of[IO] {
            case GET -> Root / "v1" / "me" / "player" =>
              IO.realTime.flatMap { now =>
                val id = setup.trackAt(now)
                Ok(playing(id, setup.coverOf(id)))
              }
            case GET -> Root / "v1" / "audio-features" / _ => Ok("""{ "tempo": 128.0 }""")
          }.orNotFound
          coverApp = HttpRoutes.of[IO] { case GET -> Root / name => requests.update(_ + 1) *> png(name) }.orNotFound
          spotify = new SpotifyClient[IO](Client.fromHttpApp(spotifyApp), SpotifyCredentials("id", "secret", Some("refresh")), session, code)
          real <- AlbumCovers.fromHttp[IO](Client.fromHttpApp(coverApp))
          live <- LiveSettings.make[IO](setup.show)
          built <- SyncEngine.make[IO](
            config(setup.show), live, spotify, zone, lights, new Random(setup.seed), covers = Some(setup.wrap(real))
          )
          env = Env(built, live, requests, logs, frames)
          result <- built.run
            .evalMap(f => IO.monotonic.flatMap(t => frames.update(_ :+ (t -> f))))
            .interruptAfter(duration)
            .compile
            .drain
            .background
            .surround(body(env))
        } yield result
      }
    }

  private def settle: IO[Unit] = IO.sleep(3.seconds)
  private def randomBase(seed: Long): RGB = RGB.random(using new Random(seed)).get
  private def oldDraw(seed: Long): RGB = {
    val r = new Random(seed)
    RGB.safe(r.nextInt(256), r.nextInt(256), r.nextInt(256)).get
  }
  private def isPale(c: RGB): Boolean = saturation(c) < 0.6 || brightness(c) < 0.5

  private def assertVividAndLogged(env: Env, show: TrackShow): Unit = {
    assert(saturation(show.base) >= 0.6, show.base.hex)
    assert(brightness(show.base) >= 0.5, show.base.hex)
    val logged = Tls.logged(env.logs).exists { e =>
      e.getLevel == Level.INFO && e.getLoggerName.endsWith("SyncEngine") && e.getFormattedMessage.contains(s"random base ${show.base.hex}")
    }
    assert(logged, show.base.hex)
  }
  private def isResting(f: Frame): Boolean = f.updates.forall(_.color == resting)

  test("a red cover gives a red base and the palette built from it") {
    world(Setup()) { env =>
      for {
        _ <- settle
        show <- env.engine.showFor(track1)
        palette <- env.engine.palette
      } yield {
        assert(hueDistance(hue(show.base), 0.0) < 0.03, show.base.hex)
        assertEquals(palette, Some(Palette.from(show.base, PaletteType.Triadic).get))
      }
    }
  }

  test("a blue cover gives a blue base") {
    world(Setup(coverOf = _ => cover("blue"))) { env =>
      settle *> env.engine.showFor(track1).map(show => assert(hueDistance(hue(show.base), 240.0 / 360) < 0.03, show.base.hex))
    }
  }

  List("pastel" -> 200.0, "dull" -> 30.0).foreach { (name, hueDegrees) =>
    test(s"a $name cover gives a base of its hue lifted to the base minimums and logged as from the cover") {
      world(Setup(coverOf = _ => cover(name), seed = 4L)) { env =>
        for {
          _ <- settle
          show <- env.engine.showFor(track1)
        } yield {
          assert(hueDistance(hue(show.base), hueDegrees / 360.0) < 0.01, show.base.hex)
          assert(saturation(show.base) >= 0.6, show.base.hex)
          assert(brightness(show.base) >= 0.5, show.base.hex)
          val logged = Tls.logged(env.logs).exists { e =>
            e.getLevel == Level.INFO && e.getLoggerName.endsWith("SyncEngine") &&
            e.getFormattedMessage.contains(s"base ${show.base.hex} from the album cover")
          }
          assert(logged, show.base.hex)
        }
      }
    }
  }

  test("the palette endpoint lists the cover's palette") {
    world(Setup()) { env =>
      val controls = new ShowControls[IO] {
        def applyNow: IO[Unit] = env.engine.applyNow
        def areas: IO[List[MusicArea]] = IO.pure(Nil)
        def palette: IO[Vector[RGB]] = env.engine.palette.map(_.fold(Vector.empty)(_.colors))
      }
      for {
        _ <- settle
        show <- env.engine.showFor(track1)
        response <- SettingsRoutes[IO](env.live, controls).orNotFound.run(Request(Method.GET, uri"/api/palette"))
        hexes <- response.as[String].map(io.circe.parser.decode[List[String]](_).toOption.get)
      } yield assertEquals(hexes, Palette.from(show.base, PaletteType.Triadic).get.colors.map(_.hex).toList)
    }
  }

  test("a mostly neutral cover gives a random base and no warning") {
    world(Setup(coverOf = _ => cover("grey"), seed = 4L)) { env =>
      for {
        _ <- settle
        show <- env.engine.showFor(track1)
        n <- env.requests.get
      } yield {
        assertEquals(show.base, randomBase(4L))
        assert(isPale(oldDraw(4L)), oldDraw(4L).hex)
        assertVividAndLogged(env, show)
        assertEquals(n, 1)
        assertEquals(env.warnings(track1), Nil)
      }
    }
  }

  List("error" -> "refused", "refused" -> "unreachable", "garbage" -> "unreadable").foreach { (name, reason) =>
    test(s"a cover that fails with $reason gives a random base and one warning") {
      world(Setup(coverOf = _ => cover(name), seed = 4L)) { env =>
        for {
          _ <- settle
          show <- env.engine.showFor(track1)
        } yield {
          assertEquals(show.base, randomBase(4L))
          assert(isPale(oldDraw(4L)), oldDraw(4L).hex)
          assertVividAndLogged(env, show)
          assertEquals(env.warnings(track1).map(_.getMDCPropertyMap.get("reason")), List(reason))
        }
      }
    }
  }

  test("a cover that arrives late is left for the next song with the same cover") {
    val setup = Setup(coverOf = _ => cover("slow"), seed = 4L, wrap = real => url => real.baseColour(url).uncancelable)
    world(setup, duration = 8.seconds) { env =>
      for {
        _ <- IO.sleep(5.seconds)
        show <- env.engine.showFor(track1)
        palette <- env.engine.palette
        frames <- env.frames.get
        next <- env.engine.showFor(track2, cover("slow"))
        n <- env.requests.get
      } yield {
        val firstPlaying = frames.collectFirst { case (t, f) if !isResting(f) => t }.get
        assert(firstPlaying <= 1.second + SyncEngine.CoverWait + 100.millis, firstPlaying.toString)
        assertEquals(show.base, randomBase(4L))
        assert(isPale(oldDraw(4L)), oldDraw(4L).hex)
        assertVividAndLogged(env, show)
        assertEquals(palette, Some(Palette.from(randomBase(4L), PaletteType.Triadic).get))
        assertEquals(env.warnings(track1).map(_.getMDCPropertyMap.get("reason")), List("timed_out"))
        assert(hueDistance(hue(next.base), 0.0) < 0.03, next.base.hex)
        assertEquals(n, 1)
      }
    }
  }

  test("two songs with the same cover get the same base from one request") {
    val setup = Setup(trackAt = t => if (t < 2.seconds) "track-1" else "track-2")
    world(setup) { env =>
      for {
        _ <- IO.sleep(1500.millis)
        first <- env.engine.showFor(track1)
        _ <- IO.sleep(2.seconds)
        second <- env.engine.showFor(track2)
        n <- env.requests.get
      } yield {
        assertEquals(first.base, second.base)
        assert(hueDistance(hue(first.base), 0.0) < 0.03)
        assertEquals(n, 1)
      }
    }
  }

  test("a song with no album makes no cover request, takes a random base and logs no warning") {
    world(Setup(coverOf = _ => None, seed = 4L)) { env =>
      for {
        _ <- settle
        show <- env.engine.showFor(track1)
        n <- env.requests.get
      } yield {
        assertEquals(show.base, randomBase(4L))
        assert(isPale(oldDraw(4L)), oldDraw(4L).hex)
        assertVividAndLogged(env, show)
        assertEquals(n, 0)
        assertEquals(env.warnings(track1), Nil)
      }
    }
  }

  test("apply now builds the new palette type from the cover's base") {
    world(Setup()) { env =>
      for {
        _ <- settle
        before <- env.engine.showFor(track1)
        _ <- env.live.update(triadic.copy(palette = PaletteType.Tetradic)) *> env.engine.applyNow
        after <- env.engine.showFor(track1)
      } yield {
        assertEquals(after.base, before.base)
        assertEquals(after.palette, Palette.from(before.base, PaletteType.Tetradic).get)
      }
    }
  }

  test("with the setting off there's no cover request and the palette is random") {
    val off = triadic.copy(albumCoverColours = false)
    world(Setup(show = off, seed = 4L)) { env =>
      for {
        _ <- settle
        show <- env.engine.showFor(track1)
        n <- env.requests.get
      } yield {
        assertEquals(n, 0)
        assertEquals(show.palette, Palette.random(PaletteType.Triadic)(using new Random(4L)).get)
        assert(isPale(oldDraw(4L)), oldDraw(4L).hex)
        assertVividAndLogged(env, show)
      }
    }
  }

  test("turning the setting off during a song keeps that song's show and the next song makes no request") {
    val setup = Setup(
      seed = 4L,
      coverOf = id => if (id == "track-1") cover("red") else cover("blue"),
      trackAt = t => if (t < 3.seconds) "track-1" else "track-2"
    )
    world(setup) { env =>
      for {
        _ <- IO.sleep(1500.millis)
        _ <- env.live.update(triadic.copy(albumCoverColours = false))
        _ <- IO.sleep(500.millis)
        first <- env.engine.showFor(track1)
        _ <- IO.sleep(3.seconds)
        second <- env.engine.showFor(track2)
        n <- env.requests.get
      } yield {
        assert(hueDistance(hue(first.base), 0.0) < 0.03, first.base.hex)
        assertEquals(second.base, randomBase(4L))
        assert(isPale(oldDraw(4L)), oldDraw(4L).hex)
        assertVividAndLogged(env, second)
        assertEquals(n, 1)
      }
    }
  }
}
