package beat

import audio.AudioCapture
import cats.effect.Async
import cats.syntax.all.*
import fs2.Stream

object BeatTracking {

  /**
   * What the music playing into `device` is doing, updated with every hop of audio. The tracker reads its tuning from
   * `settings` as it goes, so it takes up new settings when the show does.
   */
  def listen[F[_]: Async](device: String, settings: F[TrackerSettings]): Stream[F, AudioReading] =
    Stream.eval(settings).flatMap { first =>
      AudioCapture.frames[F](device, first.hop)
        .evalScan(BeatTracker.initial(first))((tracker, frame) => settings.map(s => tracker.copy(settings = s).step(frame)))
        .drop(1)
        .map(_.reading)
    }
}
