package beat

import scala.concurrent.duration.FiniteDuration

/** One block of captured audio, mixed to mono, and when it was captured (monotonic clock). */
case class AudioFrame(samples: Array[Float], capturedAt: FiniteDuration)
