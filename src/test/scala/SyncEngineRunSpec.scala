import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import client.SpotifyClient
import domain.config.*
import domain.engine.{Palette, PaletteType, RGB}
import domain.hue.{Frame, HueCredentials, Zone}
import domain.spotify.SpotifyCredentials
import domain.spotify.types.TrackId
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import scala.concurrent.duration.*
import scala.util.Random
import testkit.Colours.close

/** Runs the whole engine against a fake Spotify on a simulated clock. */
class SyncEngineRunSpec extends CatsEffectSuite {

  private val zone = Zone("00000000-0000-0000-0000-000000000000", List(0, 1, 2, 4, 7))
  private val track = TrackId("track-1")
  private val playing =
    """{ "progress_ms": 0, "is_playing": true, "item": { "id": "track-1", "album": { "images": [ { "url": "cover" } ] } } }"""
  private val resting = RGB.safe(30, 20, 8).get

  private def config(show: LightShowConfig): AppConfig =
    AppConfig(
      SpotifyCredentials("id", "secret", "refresh"),
      HueConfig(zone.id, HueCredentials("10.0.0.2", "user", "00ff")),
      pollingIntervalMs = 1000,
      lightShow = show
    )

  private case class Setup(
    show: LightShowConfig = LightShowConfig(),
    playingUntil: FiniteDuration = 1.hour,
    tempo: IO[Response[IO]] = Ok("""{ "tempo": 128.0 }"""),
    seed: Long = 1L
  )

  private def engine(setup: Setup, tempoCalls: Ref[IO, Int]): IO[SyncEngine[IO]] =
    for {
      token <- Ref.of[IO, Option[String]](Some("token"))
      code <- Deferred[IO, String]
      app = HttpRoutes.of[IO] {
        case GET -> Root / "v1" / "me" / "player" =>
          IO.realTime.flatMap(now => if (now < setup.playingUntil) Ok(playing) else NoContent())
        case GET -> Root / "v1" / "audio-features" / _ =>
          tempoCalls.update(_ + 1) *> setup.tempo
      }.orNotFound
      spotify = new SpotifyClient[IO](Client.fromHttpApp(app), SpotifyCredentials("id", "secret", "refresh"), token, code)
      built <- SyncEngine.make[IO](config(setup.show), spotify, zone, new Random(setup.seed))
    } yield built

  /** Every frame emitted in `duration` of simulated time, with the time it was emitted, plus the engine's view of the track. */
  private def run(setup: Setup, duration: FiniteDuration): IO[(List[(FiniteDuration, Frame)], TrackShow, Int)] =
    TestControl.executeEmbed {
      for {
        calls <- Ref.of[IO, Int](0)
        e <- engine(setup, calls)
        frames <- e.run.evalMap(f => IO.monotonic.map(_ -> f)).interruptAfter(duration).compile.toList
        show <- e.showFor(track)
        n <- calls.get
      } yield (frames, show, n)
    }

  private def isResting(f: Frame): Boolean = f.updates.forall(_.color == resting)

  test("sequence numbers count up with every frame and wrap after 255") {
    run(Setup(), 6.seconds).map { case (frames, _, _) =>
      assert(frames.size > 300, frames.size)
      assertEquals(frames.map(_._2.sequence), frames.indices.map(_ % 256).toList)
    }
  }

  test("pulses at the track's tempo, fetched once per track") {
    run(Setup(show = LightShowConfig(beatsPerPulse = 2)), 5.seconds).map { case (_, show, calls) =>
      assertEquals(show.bpm, 64.0)
      assertEquals(calls, 1)
    }
  }

  test("falls back to the default BPM when Spotify won't give a tempo") {
    run(Setup(show = LightShowConfig(defaultBpm = 90), tempo = Forbidden()), 3.seconds).map { case (_, show, _) =>
      assertEquals(show.bpm, 90.0)
    }
  }

  test("falls back to the default BPM when the tempo request fails") {
    run(Setup(show = LightShowConfig(defaultBpm = 45), tempo = IO.raiseError(new RuntimeException("network down"))), 3.seconds)
      .map { case (_, show, _) => assertEquals(show.bpm, 45.0) }
  }

  test("uses the configured palette type") {
    run(Setup(show = LightShowConfig(palette = PaletteType.Tetradic), seed = 42L), 3.seconds).map { case (_, show, _) =>
      assertEquals(show.palette, Palette.random(PaletteType.Tetradic)(using new Random(42L)).get)
    }
  }

  test("while playing, each of the zone's channels shows its palette colour") {
    val flat = LightShowConfig(palette = PaletteType.Triadic, pulse = PulseSettings(restLevel = 1.0, peakBoost = 0.0))
    run(Setup(show = flat, seed = 7L), 4.seconds).map { case (frames, show, _) =>
      val playingFrames = frames.collect { case (t, f) if t > 1100.millis => f }
      assert(playingFrames.nonEmpty)
      playingFrames.foreach { f =>
        assertEquals(f.updates.map(_.id), zone.channels)
        f.updates.zipWithIndex.foreach((u, i) => assert(close(u.color, show.palette.getColor(i)), s"light $i: ${u.color}"))
      }
    }
  }

  test("with nothing playing, every light shows the resting colour") {
    run(Setup(playingUntil = Duration.Zero), 4.seconds).map { case (frames, _, _) =>
      assert(frames.nonEmpty)
      assert(frames.forall((_, f) => isResting(f) && f.updates.map(_.id) == zone.channels))
    }
  }

  test("when playback stops, the lights fade to the resting colour and hold it") {
    run(Setup(playingUntil = 3.seconds), 8.seconds).map { case (frames, _, _) =>
      def distance(f: Frame) = f.updates.map(u => u.color.toVector.zip(resting.toVector).map((a, b) => math.abs(a - b)).sum).sum
      val beforeStop = frames.collect { case (t, f) if t > 1100.millis && t < 2900.millis => f }
      val fading = frames.collect { case (t, f) if t > 3100.millis && t < 4900.millis => f }
      val afterFade = frames.collect { case (t, f) if t > 5100.millis => f }
      assert(beforeStop.exists(f => !isResting(f)), "the lights should be pulsing before the stop")
      assert(fading.exists(f => !isResting(f)), "the lights should still be fading")
      val distances = frames.collect { case (t, f) if t > 3100.millis => distance(f) }
      assert(distances.zip(distances.drop(1)).forall((a, b) => b <= a), "the lights should only move towards the resting colour")
      assert(afterFade.nonEmpty && afterFade.forall(isResting))
    }
  }
}
