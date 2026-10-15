package settings

import cats.effect.IO
import domain.config.{AudioSettings, BeatSource, LightShowConfig, PulseSettings, SettingsProblems}
import cats.syntax.all.*
import munit.CatsEffectSuite

class LiveSettingsSpec extends CatsEffectSuite {

  private val fromFile = LightShowConfig(beatSource = BeatSource.Audio)
  private val sharper = fromFile.copy(pulse = PulseSettings().copy(attack = 0.05))

  test("a change waits for the next song, then the show takes it up") {
    for {
      live <- LiveSettings.make[IO](fromFile)
      _ <- live.update(sharper)
      waiting <- live.view
      started <- live.startSong
      playing <- live.view
    } yield {
      assertEquals((waiting.pending, waiting.applied), (sharper, fromFile))
      assertEquals(started, sharper)
      assertEquals((playing.pending, playing.applied), (sharper, sharper))
    }
  }

  test("settings that can't work are turned down, and the pending ones stay as they were") {
    val broken = fromFile.copy(pulse = PulseSettings().copy(attack = 0.7, sustain = 0.5))
    for {
      live <- LiveSettings.make[IO](fromFile)
      answer <- live.update(broken)
      view <- live.view
    } yield {
      assertEquals(answer, Left(SettingsProblems(List("pulse.attack + pulse.sustain must be less than 1"))))
      assertEquals(view.pending, fromFile)
    }
  }

  test("what only changes on a restart can't be changed") {
    val changes = List(
      fromFile.copy(beatSource = BeatSource.Spotify) -> "beat-source only changes when the app restarts",
      fromFile.copy(audio = AudioSettings(device = "Loopback")) -> "audio.device only changes when the app restarts",
      fromFile.copy(frameIntervalMs = 33) -> "frame-interval-ms only changes when the app restarts"
    )
    LiveSettings.make[IO](fromFile).flatMap { live =>
      changes.traverse_((next, problem) => live.update(next).assertEquals(Left(SettingsProblems(List(problem)))))
    }
  }

  test("reset goes back to the config file's settings for the next song") {
    for {
      live <- LiveSettings.make[IO](fromFile)
      _ <- live.update(sharper) *> live.startSong
      view <- live.reset
    } yield assertEquals((view.pending, view.applied, view.fromFile), (fromFile, sharper, fromFile))
  }
}
