package audio

import scala.concurrent.duration.*

/**
 * Works out when each block of audio was captured from how many samples have arrived, rather than from when the
 * read returned. Reads return in bursts, some promptly and some with audio that has been waiting, and stamping
 * waiting audio with the time it was read would make every beat look later than it was. The sample count is
 * anchored to the promptest read among the last `window` reads, so it follows the audio device's clock without
 * drifting from the system's.
 */
case class CaptureClock(sampleRate: Double, window: Int, samples: Long = 0, lags: Vector[Long] = Vector.empty) {

  /** Records a read of `count` samples that returned at `readAt`; returns when the last of them was captured. */
  def next(count: Int, readAt: FiniteDuration): (CaptureClock, FiniteDuration) = {
    val total = samples + count
    val audioTime = (total / sampleRate * 1e9).toLong
    val recent = (lags :+ (readAt.toNanos - audioTime)).takeRight(window)
    (copy(samples = total, lags = recent), (audioTime + recent.min).nanos)
  }
}
