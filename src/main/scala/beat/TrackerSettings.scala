package beat

import scala.concurrent.duration.*

case class TrackerSettings(
  sampleRate: Double = 44100,
  hop: Int = 512,
  window: Int = 1024,
  historySeconds: Double = 8.0,
  minBpm: Double = 60,
  maxBpm: Double = 180,
  reestimateEvery: Int = 22,
  silenceRms: Double = 0.002,
  minConfidence: Double = 2.5,
  keepConfidence: Double = 1.2,
  steadySeconds: Double = 3.0,
  phaseSwitch: Double = 0.6,
  phaseMemorySeconds: Double = 10.0,
  holdSeconds: Double = 10.0,
  hitRatio: Double = 1.15,
  sparkleRatio: Double = 4.0
) {
  val frameRate: Double = sampleRate / hop
  val hopDuration: FiniteDuration = (hop / sampleRate).seconds.toNanos.nanos
  val capacity: Int = (historySeconds * frameRate).toInt
  val minFrames: Int = (4.0 * frameRate).toInt
  val hitWindow: Int = (4.0 * frameRate).toInt
  val sparkleWindow: Int = (2.0 * frameRate).toInt
  val steadyEstimates: Int = math.max(1, (steadySeconds * frameRate / reestimateEvery).round.toInt)

  /** Smoothing factor of an exponential average with the given time constant, per hop. */
  def smoothing(seconds: Double): Double = 1 - math.exp(-(hop / sampleRate) / seconds)
}
