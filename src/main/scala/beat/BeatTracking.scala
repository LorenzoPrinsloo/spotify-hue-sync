package beat

import audio.AudioInput
import cats.effect.Async
import domain.config.AudioSettings
import fs2.Stream

object BeatTracking {

  /** The live beat of whatever is playing into the configured audio input; None while nothing has a clear beat. */
  def beats[F[_]: Async](settings: AudioSettings): Stream[F, Option[BeatState]] = {
    val tracker = TrackerSettings(minBpm = settings.minBpm, maxBpm = settings.maxBpm)
    AudioInput.frames[F](settings.device, tracker.hop)
      .scan(BeatTracker.initial(tracker))(_.step(_))
      .map(_.beat)
      .filterWithPrevious(_ != _)
  }
}
