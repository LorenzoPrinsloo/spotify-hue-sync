package beat

import scala.concurrent.duration.*
import scala.util.Random

/** Generated test audio: drum-like hits at known times, cut into the frames the tracker reads. */
object Synth {

  val settings: TrackerSettings = TrackerSettings()
  private val sr = settings.sampleRate

  /** A short decaying burst: a low thump for kicks, or bright noise for hi-hats. */
  private def hit(buffer: Array[Float], at: Double, loudness: Double, bright: Boolean, rnd: Random): Unit = {
    val start = (at * sr).toInt
    val length = (0.08 * sr).toInt
    for (i <- 0 until length if start + i >= 0 && start + i < buffer.length) {
      val t = i / sr
      val env = math.exp(-t * (if (bright) 60 else 25))
      val tone = if (bright) rnd.nextGaussian() * 0.5 else math.sin(2 * math.Pi * 60 * t) + 0.3 * rnd.nextGaussian()
      buffer(start + i) = (buffer(start + i) + loudness * env * tone).toFloat
    }
  }

  /**
   * `seconds` of audio with a kick on every beat (starting at `firstBeat` seconds), optional quieter hi-hats
   * half way between beats, and a little background noise. `bpmAt` gives the tempo at each moment.
   */
  def drums(seconds: Double, bpmAt: Double => Double, firstBeat: Double = 0.25, hats: Boolean = true, seed: Long = 1L): (Array[Float], Vector[Double]) = {
    val rnd = new Random(seed)
    val buffer = Array.fill((seconds * sr).toInt)((rnd.nextGaussian() * 0.01).toFloat)
    val beats = Iterator.iterate(firstBeat)(t => t + 60.0 / bpmAt(t)).takeWhile(_ < seconds).toVector
    beats.foreach { t =>
      hit(buffer, t, 0.8, bright = false, rnd)
      if (hats) hit(buffer, t + 30.0 / bpmAt(t), 0.25, bright = true, rnd)
    }
    (buffer, beats)
  }

  def silence(seconds: Double): Array[Float] = new Array[Float]((seconds * sr).toInt)

  /** Cuts audio into hop-sized frames, each stamped with the time its last sample was captured, plus `latency`. */
  def frames(audio: Array[Float], latency: FiniteDuration = Duration.Zero): Iterator[AudioFrame] =
    audio.grouped(settings.hop).zipWithIndex.map { (samples, i) =>
      AudioFrame(samples, ((i + 1) * settings.hop / sr).seconds.toNanos.nanos + latency)
    }

  /** Runs the tracker over all the audio and returns its final state. */
  def track(audio: Array[Float], start: BeatTracker = BeatTracker.initial(settings)): BeatTracker =
    frames(audio).foldLeft(start)(_.step(_))

  /**
   * The worst distance, in milliseconds, between a predicted beat in the last few seconds and the nearest actual
   * beat. When the tracker counts double time, half-beats count as beats too.
   */
  def phaseErrorMs(beat: BeatState, actualBeats: Vector[Double]): Double = {
    val predictedPeriod = 60.0 / beat.bpm
    val actualPeriod = if (actualBeats.size > 1) actualBeats(1) - actualBeats(0) else predictedPeriod
    val doubleTime = predictedPeriod < 0.75 * actualPeriod
    val targets = if (doubleTime) actualBeats.flatMap(t => Vector(t, t + actualPeriod / 2)) else actualBeats
    val end = beat.beatAt.toNanos / 1e9
    val predicted = Iterator.iterate(end)(_ - predictedPeriod).takeWhile(_ > end - 3).toVector
    predicted.map(p => targets.map(t => math.abs(t - p)).min * 1000).max
  }
}
