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
   * `seconds` of audio with a kick on every `kickEvery`th beat (starting at `firstBeat` seconds), optional quieter
   * hi-hats half way between beats, and a little background noise. `bpmAt` gives the tempo at each moment.
   */
  def drums(
    seconds: Double,
    bpmAt: Double => Double,
    firstBeat: Double = 0.25,
    hats: Boolean = true,
    seed: Long = 1L,
    loudness: Double = 1.0,
    kicks: Boolean = true,
    hatLoudness: Double = 0.25,
    kickEvery: Int = 1
  ): (Array[Float], Vector[Double]) = {
    val rnd = new Random(seed)
    val buffer = Array.fill((seconds * sr).toInt)((rnd.nextGaussian() * 0.01 * loudness).toFloat)
    val beats = Iterator.iterate(firstBeat)(t => t + 60.0 / bpmAt(t)).takeWhile(_ < seconds).toVector
    beats.zipWithIndex.foreach { (t, i) =>
      if (kicks && i % kickEvery == 0) hit(buffer, t, 0.8 * loudness, bright = false, rnd)
      if (hats) hit(buffer, t + 30.0 / bpmAt(t), hatLoudness * loudness, bright = true, rnd)
    }
    (buffer, beats)
  }

  /** A bass note: a sine at `hz` for `length` seconds, with a quick attack and a gentle fade. */
  private def note(buffer: Array[Float], at: Double, loudness: Double, hz: Double, length: Double): Unit = {
    val start = (at * sr).toInt
    for (i <- 0 until (length * sr).toInt if start + i >= 0 && start + i < buffer.length) {
      val t = i / sr
      val env = math.min(1.0, t / 0.004) * math.exp(-t * 6)
      buffer(start + i) = (buffer(start + i) + loudness * env * math.sin(2 * math.Pi * hz * t)).toFloat
    }
  }

  /**
   * `seconds` of audio at a steady `bpm` whose first beat is at `firstBeat` seconds, with a kick thump and a bass note
   * on the sixteenths where `kicks` and `bass` (given the sixteenth counted from the first beat) are above 0, scaled by
   * `barLoudness` of the bar, and hi-hats on the eighths. Returns the audio and the time of each sixteenth.
   */
  def pattern(
    seconds: Double,
    bpm: Double,
    kicks: Int => Double,
    bass: Int => Double = _ => 0.0,
    barLoudness: Int => Double = _ => 1.0,
    hats: Int => Boolean = _ => true,
    seed: Long = 1L,
    firstBeat: Double = 0.25,
    hatLoudness: Double = 0.25,
    bassHz: Double = 80
  ): (Array[Float], Vector[Double]) = {
    val rnd = new Random(seed)
    val buffer = Array.fill((seconds * sr).toInt)((rnd.nextGaussian() * 0.01).toFloat)
    val sixteenth = 15.0 / bpm
    val times = Iterator.from(0).map(firstBeat + _ * sixteenth).takeWhile(_ < seconds).toVector
    times.zipWithIndex.foreach { (t, step) =>
      val loud = barLoudness(step / 16)
      if (kicks(step) > 0) hit(buffer, t, 0.8 * kicks(step) * loud, bright = false, rnd)
      if (bass(step) > 0) note(buffer, t, 0.8 * bass(step) * loud, bassHz, sixteenth)
      if (step % 2 == 0 && hats(step / 16)) hit(buffer, t, hatLoudness * loud, bright = true, rnd)
    }
    (buffer, times)
  }

  /** Loud bursts of mid and high tones with next to nothing below 300 Hz: they move the onsets but not the kick. */
  def accents(seconds: Double, bpmAt: Double => Double, firstBeat: Double = 0.1, loudness: Double = 1.0, seed: Long = 5L): Array[Float] = {
    val rnd = new Random(seed)
    val buffer = new Array[Float]((seconds * sr).toInt)
    val times = Iterator.iterate(firstBeat)(t => t + 60.0 / bpmAt(t)).takeWhile(_ < seconds)
    times.foreach { at =>
      val start = (at * sr).toInt
      val raw = Array.fill((0.08 * sr).toInt + 4)(rnd.nextGaussian())
      for (i <- 0 until raw.length - 4 if start + i < buffer.length) {
        val t = i / sr
        val highPassed = raw(i + 4) - 4 * raw(i + 3) + 6 * raw(i + 2) - 4 * raw(i + 1) + raw(i)
        buffer(start + i) =
          (buffer(start + i) + loudness * (1 - math.exp(-t * 1500)) * math.exp(
            -t * 40
          ) * math.pow(math.cos(math.Pi / 2 * i / (raw.length - 4)), 2) * highPassed * 0.07).toFloat
      }
    }
    buffer
  }

  /** A cymbal crash at `at` seconds: loud, bright and long, on top of the given audio. */
  def withCrash(audio: Array[Float], at: Double, loudness: Double = 1.5, seed: Long = 2L): Array[Float] = {
    val rnd = new Random(seed)
    val out = audio.clone()
    val start = (at * sr).toInt
    for (i <- 0 until (0.6 * sr).toInt if start + i < out.length) {
      val t = i / sr
      out(start + i) = (out(start + i) + loudness * math.exp(-t * 6) * rnd.nextGaussian() * 0.5).toFloat
    }
    out
  }

  /** A held chord with no attacks: loud, but nothing for a beat tracker to lock onto. */
  def pad(seconds: Double, loudness: Double = 0.3): Array[Float] =
    Array.tabulate((seconds * sr).toInt) { i =>
      val t = i / sr
      val fadeIn = math.min(1.0, t / 0.5)
      (loudness * fadeIn * (math.sin(2 * math.Pi * 220 * t) + math.sin(2 * math.Pi * 277 * t) + math.sin(2 * math.Pi * 330 * t)) / 3).toFloat
    }

  /** A steady sine tone at `hz`, like a held note. */
  def tone(seconds: Double, hz: Double, loudness: Double = 0.3): Array[Float] =
    Array.tabulate((seconds * sr).toInt)(i => (loudness * math.sin(2 * math.Pi * hz * i / sr)).toFloat)

  /** Plays one piece of audio after another. */
  def concat(parts: Array[Float]*): Array[Float] = parts.flatten.toArray

  /** Plays pieces of audio at the same time, as long as the longest. */
  def mix(parts: Array[Float]*): Array[Float] =
    Array.tabulate(parts.map(_.length).max)(i => parts.map(p => if (i < p.length) p(i) else 0f).sum)

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

  /** Every reading the tracker gives while listening to the audio, with the time in seconds. */
  def readings(audio: Array[Float], settings: TrackerSettings = settings): Vector[(Double, AudioReading)] =
    frames(audio).scanLeft(BeatTracker.initial(settings))(_.step(_)).drop(1)
      .map(t => (t.frames * settings.hop / settings.sampleRate, t.reading)).toVector

  /** The reading closest to the given time. */
  def at(readings: Vector[(Double, AudioReading)], seconds: Double): AudioReading =
    readings.minBy((t, _) => math.abs(t - seconds))._2

  /** When each big hit happened, in seconds. */
  def hitTimes(readings: Vector[(Double, AudioReading)]): Vector[Double] =
    readings.flatMap((_, r) => r.hit.map(_.at.toNanos / 1e9)).distinct

  def noise(seconds: Double, loudness: Double = 0.2, seed: Long = 3L): Array[Float] = {
    val rnd = new Random(seed)
    Array.fill((seconds * sr).toInt)((rnd.nextGaussian() * loudness).toFloat)
  }
}
