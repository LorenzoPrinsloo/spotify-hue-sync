package beat

import audio.AudioInput
import cats.effect.Async
import cats.syntax.all.*
import domain.config.LightShowConfig
import fs2.Stream

object BeatTracking {

  /** The tracker's tuning, from the light show's settings. */
  def trackerSettings(show: LightShowConfig): TrackerSettings =
    TrackerSettings(
      minBpm = show.audio.minBpm,
      maxBpm = show.audio.maxBpm,
      holdSeconds = show.audio.holdSeconds,
      hitRatio = show.reactive.hitRatio,
      sparkleRatio = show.reactive.sparkleRatio
    )

  /**
   * What the music playing into `device` is doing, updated with every hop of audio. The tracker reads its tuning from
   * `settings` as it goes, so it takes up new settings when the show does.
   */
  def listen[F[_]: Async](device: String, settings: F[TrackerSettings]): Stream[F, AudioReading] =
    Stream.eval(settings).flatMap { first =>
      AudioInput.frames[F](device, first.hop)
        .evalScan(BeatTracker.initial(first))((tracker, frame) => settings.map(s => tracker.copy(settings = s).step(frame)))
        .drop(1)
        .map(_.reading)
    }
}
