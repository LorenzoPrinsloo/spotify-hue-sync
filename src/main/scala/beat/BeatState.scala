package beat

import scala.concurrent.duration.*

/**
 * The current tempo, and the time of one beat; every other beat follows from those two. `confidence` is how far
 * above average the onsets on the beat grid are, in standard deviations. `doubt` is how much stronger another
 * phase has been than this grid's, built up over recent estimates. `number` counts the beats from locking on to
 * `beatAt`, so the count carries on from one estimate to the next.
 */
case class BeatState(
  bpm: Double,
  beatAt: FiniteDuration,
  updatedAt: FiniteDuration,
  confidence: Double = 1.0,
  doubt: Double = 0.0,
  number: Long = 0
) {
  def period: FiniteDuration = (60.0 / bpm).seconds.toNanos.nanos

  /** Beats elapsed between `beatAt` and `time`; the fractional part is the position within the current beat. */
  def beatsAt(time: FiniteDuration): Double = (time - beatAt).toNanos.toDouble / period.toNanos

  /** Beats counted since locking on, at `time`. */
  def countAt(time: FiniteDuration): Double = number + beatsAt(time)
}
