import cats.effect.{Deferred, IO, Ref}
import beat.{AudioReading, BeatState, Hit}
import cats.effect.testkit.TestControl
import fs2.Stream
import client.SpotifyClient
import domain.config.*
import domain.engine.{LightRole, Palette, PaletteType, Pulse, RGB}
import settings.LiveSettings
import domain.hue.{Frame, HueCredentials, MusicArea, Zone}
import domain.spotify.{SpotifyCredentials, SpotifySession}
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
  private def playing(trackId: String): String =
    s"""{ "progress_ms": 0, "is_playing": true, "item": { "id": "$trackId", "album": { "images": [ { "url": "cover" } ] } } }"""
  private val resting = RGB.safe(30, 20, 8).get

  private def config(show: LightShowConfig): AppConfig =
    AppConfig(
      SpotifyCredentials("id", "secret", "refresh"),
      HueConfig(HueCredentials("10.0.0.2", "user", "00ff"), Some(zone.id)),
      pollingIntervalMs = 1000,
      lightShow = show
    )

  private val upstairs = zone
  private val downstairs = Zone("11111111-1111-1111-1111-111111111111", List(3, 5))

  /** A bridge with two areas: upstairs, the zone the show starts on, and downstairs. */
  private val lights: Lights[IO] = new Lights[IO] {
    def areas: IO[List[MusicArea]] = IO.pure(Nil)
    def zone(area: String): IO[Option[Zone]] =
      IO.pure(area match {
        case "Upstairs"   => Some(upstairs)
        case "Downstairs" => Some(downstairs)
        case _            => None
      })
  }

  private case class Setup(
    show: LightShowConfig = LightShowConfig(),
    playingUntil: FiniteDuration = 1.hour,
    tempo: IO[Response[IO]] = Ok("""{ "tempo": 128.0 }"""),
    seed: Long = 1L,
    audio: Stream[IO, AudioReading] = Stream.empty,
    trackAt: FiniteDuration => String = _ => "track-1",
    pollFails: Boolean = false
  )

  private def engine(setup: Setup, tempoCalls: Ref[IO, Int]): IO[(SyncEngine[IO], LiveSettings[IO])] =
    for {
      session <- Ref.of[IO, Option[SpotifySession]](Some(SpotifySession("token", "refresh")))
      code <- Deferred[IO, String]
      app = HttpRoutes.of[IO] {
        case GET -> Root / "v1" / "me" / "player" =>
          if (setup.pollFails) InternalServerError("busy")
          else IO.realTime.flatMap(now => if (now < setup.playingUntil) Ok(playing(setup.trackAt(now))) else NoContent())
        case GET -> Root / "v1" / "audio-features" / _ =>
          tempoCalls.update(_ + 1) *> setup.tempo
      }.orNotFound
      spotify = new SpotifyClient[IO](Client.fromHttpApp(app), SpotifyCredentials("id", "secret", "refresh"), session, code)
      live <- LiveSettings.make[IO](setup.show)
      built <- SyncEngine.make[IO](config(setup.show), live, spotify, zone, lights, new Random(setup.seed), setup.audio)
    } yield (built, live)

  /** Every frame emitted in `duration` of simulated time, with the time it was emitted, plus the engine's view of the track. */
  private def run(setup: Setup, duration: FiniteDuration): IO[(List[(FiniteDuration, Frame)], TrackShow, Int)] =
    TestControl.executeEmbed {
      for {
        calls <- Ref.of[IO, Int](0)
        built <- engine(setup, calls)
        e = built._1
        frames <- e.run.evalMap(f => IO.monotonic.map(_ -> f)).interruptAfter(duration).compile.toList
        show <- e.showFor(track)
        n <- calls.get
      } yield (frames, show, n)
    }

  private def isResting(f: Frame): Boolean = f.updates.forall(_.color == resting)

  test("sequence numbers count up with every frame and wrap after 255") {
    run(Setup(), 6.seconds).map { (frames, _, _) =>
      assert(frames.size > 300, frames.size)
      assertEquals(frames.map(_._2.sequence), frames.indices.map(_ % 256).toList)
    }
  }

  test("pulses at the track's tempo, fetched once per track") {
    run(Setup(show = LightShowConfig(beatsPerPulse = 2)), 5.seconds).map { (_, show, calls) =>
      assertEquals(show.bpm, 64.0)
      assertEquals(calls, 1)
    }
  }

  test("falls back to the default BPM when Spotify won't give a tempo") {
    run(Setup(show = LightShowConfig(defaultBpm = 90), tempo = Forbidden()), 3.seconds).map { (_, show, _) =>
      assertEquals(show.bpm, 90.0)
    }
  }

  test("falls back to the default BPM when the tempo request fails") {
    run(Setup(show = LightShowConfig(defaultBpm = 45), tempo = IO.raiseError(new RuntimeException("network down"))), 3.seconds)
      .map { (_, show, _) => assertEquals(show.bpm, 45.0) }
  }

  test("uses the configured palette type") {
    run(Setup(show = LightShowConfig(palette = PaletteType.Tetradic), seed = 42L), 3.seconds).map { (_, show, _) =>
      assertEquals(show.palette, Palette.random(PaletteType.Tetradic)(using new Random(42L)).get)
    }
  }

  test("while playing, each of the zone's channels shows its palette colour") {
    val flat = LightShowConfig(palette = PaletteType.Triadic, pulse = PulseSettings(restLevel = 1.0, peakBoost = 0.0))
    run(Setup(show = flat, seed = 7L), 4.seconds).map { (frames, show, _) =>
      val playingFrames = frames.collect { case (t, f) if t > 1100.millis => f }
      assert(playingFrames.nonEmpty)
      playingFrames.foreach { f =>
        assertEquals(f.updates.map(_.id), zone.channels)
        f.updates.zipWithIndex.foreach((u, i) => assert(close(u.color, show.palette.getColor(i)), s"light $i: ${u.color}"))
      }
    }
  }

  test("with nothing playing, every light shows the resting colour") {
    run(Setup(playingUntil = Duration.Zero), 4.seconds).map { (frames, _, _) =>
      assert(frames.nonEmpty)
      assert(frames.forall((_, f) => isResting(f) && f.updates.map(_.id) == zone.channels))
    }
  }

  test("when playback stops, the lights fade to the resting colour and hold it") {
    run(Setup(playingUntil = 3.seconds), 8.seconds).map { (frames, _, _) =>
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

  private val liveBeat = BeatState(bpm = 120, beatAt = 0.seconds, updatedAt = 0.seconds)

  /** The frame the engine should show at `t` when it follows Spotify's position: polled every second, from 0 ms. */
  private def spotifyFrame(t: FiniteDuration, show: TrackShow, settings: LightShowConfig): Frame = {
    val receivedAt = (t.toMillis / 1000) * 1000
    val positionMs = (t.toMillis - receivedAt) + settings.syncOffsetMs
    SyncEngine.pulseFrame(zone, show.palette, SyncEngine.pulsesAt(positionMs, show.bpm), settings.pulse)
  }

  /** Frames while the track plays, skipping the instants where a poll and a frame coincide. */
  private def playingFrames(frames: List[(FiniteDuration, Frame)]): List[(FiniteDuration, Frame)] =
    frames.filter((t, _) => t > 1100.millis && t.toMillis % 1000 > 20)

  /** Every light doing the classic travelling pulse, so the timing of the pulse itself is what's checked. */
  private val classic = LightShowConfig(reactive = ReactiveSettings(roles = List(LightRole.Pulse)))

  test("with a live beat, the pulses follow it") {
    val settings = classic
    run(Setup(show = settings, audio = Stream.emit(AudioReading(Some(liveBeat))) ++ Stream.never[IO]), 5.seconds).map { (frames, show, _) =>
      val checked = playingFrames(frames)
      assert(checked.size > 100)
      checked.foreach { (t, f) =>
        val expected = SyncEngine.pulseFrame(zone, show.palette, liveBeat.beatsAt(t + settings.audio.offsetMs.millis), settings.pulse)
        assertEquals(f.updates, expected.updates, s"at $t")
      }
    }
  }

  test("beats-per-pulse slows the pulse down relative to the live beat") {
    val settings = classic.copy(beatsPerPulse = 2)
    run(Setup(show = settings, audio = Stream.emit(AudioReading(Some(liveBeat))) ++ Stream.never[IO]), 4.seconds).map { (frames, show, _) =>
      playingFrames(frames).foreach { (t, f) =>
        val expected = SyncEngine.pulseFrame(zone, show.palette, liveBeat.beatsAt(t + settings.audio.offsetMs.millis) / 2, settings.pulse)
        assertEquals(f.updates, expected.updates, s"at $t")
      }
    }
  }

  test("without a live beat, the pulses follow Spotify's position at the track's tempo") {
    val settings = classic
    run(Setup(show = settings, audio = Stream.emit(AudioReading(None)) ++ Stream.never[IO]), 4.seconds).map { (frames, show, _) =>
      val checked = playingFrames(frames)
      assert(checked.size > 100)
      checked.foreach((t, f) => assertEquals(f.updates, spotifyFrame(t, show, settings).updates, s"at $t"))
    }
  }

  test("when the beat is lost, the pulses switch back to Spotify timing") {
    val settings = classic
    val audio = Stream.emit(AudioReading(Some(liveBeat))) ++ Stream.sleep_[IO](3.seconds) ++ Stream.emit(AudioReading(None)) ++ Stream.never[IO]
    run(Setup(show = settings, audio = audio), 6.seconds).map { (frames, show, _) =>
      playingFrames(frames).foreach { (t, f) =>
        val expected =
          if (t < 3.seconds) SyncEngine.pulseFrame(zone, show.palette, liveBeat.beatsAt(t + settings.audio.offsetMs.millis), settings.pulse)
          else spotifyFrame(t, show, settings)
        assertEquals(f.updates, expected.updates, s"at $t")
      }
    }
  }

  test("with live audio, rhythm lights pulse together on the live beat and colour lights swell every other beat") {
    val settings = LightShowConfig()
    run(Setup(audio = Stream.emit(AudioReading(Some(liveBeat))) ++ Stream.never[IO]), 5.seconds).map { (frames, show, _) =>
      val checked = playingFrames(frames)
      assert(checked.size > 100)
      val roles = LightRole.assign(settings.reactive.roles, zone.channels.size)
      checked.foreach { (t, f) =>
        val level = Pulse.onBeat(liveBeat.beatsAt(t + settings.audio.offsetMs.millis), settings.pulse)
        roles.zipWithIndex.filter(_._1 == LightRole.Rhythm).foreach { (_, i) =>
          assertEquals(f.updates(i).color, show.palette.getColor(i).scaleBrightness(Pulse.brightness(level, settings.pulse)), s"light $i at $t")
        }
      }
      val swell = settings.reactive.colourSwell
      checked.foreach { (t, f) =>
        val level = Pulse.onBeat(liveBeat.beatsAt(t + settings.audio.offsetMs.millis) / swell.beats, swell.shape)
        roles.zipWithIndex.filter(_._1 == LightRole.Colour).foreach { (_, i) =>
          assertEquals(f.updates(i).color, show.palette.along(i, 0).scaleBrightness(Pulse.brightness(level, swell.shape)), s"light $i at $t")
        }
      }
    }
  }

  private val brighter = classic.copy(pulse = classic.pulse.copy(restLevel = 0.5))

  /** Runs the engine for `duration` while `during` acts on it, returning what `during` saw. */
  private def alongside[A](setup: Setup, duration: FiniteDuration)(during: (SyncEngine[IO], LiveSettings[IO]) => IO[A]): IO[A] =
    TestControl.executeEmbed {
      for {
        calls <- Ref.of[IO, Int](0)
        seen <- engine(setup, calls).flatMap((e, live) => e.run.interruptAfter(duration).compile.drain.background.surround(during(e, live)))
      } yield seen
    }

  test("settings changed while a song plays wait for the next song") {
    val songs = Setup(show = classic, trackAt = t => if (t < 3.seconds) "track-1" else "track-2")
    alongside(songs, 5.seconds) { (e, live) =>
      for {
        _ <- IO.sleep(1500.millis) *> live.update(brighter)
        sameSong <- IO.sleep(1.second) *> e.showFor(TrackId("track-1"))
        nextSong <- IO.sleep(2.seconds) *> e.showFor(TrackId("track-2"))
      } yield (sameSong.settings, nextSong.settings)
    }.assertEquals((classic, brighter))
  }

  test("apply now takes the settings up straight away, keeping the palette unless its type changed") {
    alongside(Setup(show = classic), 4.seconds) { (e, live) =>
      for {
        before <- IO.sleep(1500.millis) *> e.showFor(track)
        _ <- live.update(brighter) *> e.applyNow
        kept <- e.showFor(track)
        _ <- live.update(brighter.copy(palette = PaletteType.Tetradic)) *> e.applyNow
        changed <- e.showFor(track)
        playing <- e.palette
      } yield {
        assertEquals(playing, Some(changed.palette))
        assertEquals(kept.settings, brighter)
        assertEquals(kept.palette, before.palette)
        assertEquals(changed.palette.colors.size, 4)
      }
    }
  }

  test("a different area waits for the next song, then the show plays there") {
    val songs = Setup(show = classic.copy(area = Some("Upstairs")), trackAt = t => if (t < 3.seconds) "track-1" else "track-2")
    TestControl.executeEmbed {
      for {
        calls <- Ref.of[IO, Int](0)
        frames <- engine(songs, calls).flatMap { (e, live) =>
          val move = IO.sleep(1500.millis) *> live.update(classic.copy(area = Some("Downstairs")))
          e.run.evalMap(f => IO.monotonic.map(_ -> f)).interruptAfter(5.seconds).compile.toList.both(move).map(_._1)
        }
      } yield frames
    }.map { frames =>
      val playing = playingFrames(frames)
      assert(playing.filter(_._1 < 2900.millis).forall(_._2.zoneId == zone.id), "before the next song")
      assert(playing.filter(_._1 > 3200.millis).forall(f => f._2.zoneId == downstairs.id && f._2.updates.map(_.id) == downstairs.channels), "after it")
      assert(playing.exists(_._1 > 3200.millis))
    }
  }

  test("apply now moves to another area straight away; an area the bridge doesn't have leaves the show where it is") {
    alongside(Setup(show = classic.copy(area = Some("Upstairs"))), 4.seconds) { (e, live) =>
      for {
        _ <- IO.sleep(1500.millis) *> live.update(classic.copy(area = Some("Downstairs"))) *> e.applyNow
        moved <- e.showFor(track)
        _ <- live.update(classic.copy(area = Some("Attic"))) *> e.applyNow
        stayed <- e.showFor(track)
      } yield assertEquals((moved.zone, stayed.zone), (downstairs, downstairs))
    }
  }

  test("a poll that fails counts as nothing playing, and the show carries on resting") {
    run(Setup(pollFails = true), 4.seconds).map { (frames, _, _) =>
      assert(frames.size > 200, frames.size)
      assert(frames.forall((_, f) => isResting(f)))
    }
  }

  test("if beat detection fails, the show carries on with Spotify timing") {
    val settings = LightShowConfig()
    run(Setup(audio = Stream.raiseError[IO](new RuntimeException("no audio device"))), 4.seconds).map { (frames, show, _) =>
      val checked = playingFrames(frames)
      assert(checked.size > 100)
      checked.foreach((t, f) => assertEquals(f.updates, spotifyFrame(t, show, settings).updates, s"at $t"))
    }
  }
}
