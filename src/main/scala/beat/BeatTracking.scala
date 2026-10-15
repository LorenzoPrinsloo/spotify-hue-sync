package beat

import audio.AudioInput
import cats.effect.Async
import domain.config.{AudioSettings, ReactiveSettings}
import fs2.Stream

object BeatTracking {

  /** What the music playing into the configured audio input is doing, updated with every hop of audio. */
  def listen[F[_]: Async](audio: AudioSettings, reactive: ReactiveSettings): Stream[F, AudioReading] = {
    val tracker = TrackerSettings(
      minBpm = audio.minBpm,
      maxBpm = audio.maxBpm,
      holdSeconds = audio.holdSeconds,
      hitRatio = reactive.hitRatio,
      sparkleRatio = reactive.sparkleRatio
    )
    AudioInput.frames[F](audio.device, tracker.hop)
      .scan(BeatTracker.initial(tracker))(_.step(_))
      .drop(1)
      .map(_.reading)
  }
}
