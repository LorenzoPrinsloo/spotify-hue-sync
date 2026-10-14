package beat

import scala.concurrent.duration.*

/** One block of captured audio, mixed to mono, and when it was captured (monotonic clock). */
case class AudioFrame(samples: Array[Float], capturedAt: FiniteDuration)

/** The current tempo, and the time of one beat; every other beat follows from those two. */
case class BeatState(bpm: Double, beatAt: FiniteDuration, updatedAt: FiniteDuration) {
  def period: FiniteDuration = (60.0 / bpm).seconds.toNanos.nanos

  /** Beats elapsed between `beatAt` and `time`; the fractional part is the position within the current beat. */
  def beatsAt(time: FiniteDuration): Double = (time - beatAt).toNanos.toDouble / period.toNanos
}

case class TrackerSettings(
  sampleRate: Double = 44100,
  hop: Int = 512,
  window: Int = 1024,
  historySeconds: Double = 8.0,
  minBpm: Double = 60,
  maxBpm: Double = 180,
  reestimateEvery: Int = 22,
  silenceRms: Double = 0.002
) {
  val frameRate: Double = sampleRate / hop
  val hopDuration: FiniteDuration = (hop / sampleRate).seconds.toNanos.nanos
  val capacity: Int = (historySeconds * frameRate).toInt
  val minFrames: Int = (4.0 * frameRate).toInt
}

/**
 * Tracks the beat of live audio. Each hop of audio becomes one onset-strength value. A few times a second the
 * tracker tries every candidate beat grid over the last few seconds of onsets and keeps the one whose beats land
 * on the strongest onsets: that grid gives both the tempo and where the beats fall.
 */
case class BeatTracker(
  settings: TrackerSettings,
  detector: OnsetDetector,
  envelope: Vector[Double],
  loudness: Vector[Double],
  frames: Long,
  beat: Option[BeatState]
) {

  def step(frame: AudioFrame): BeatTracker = {
    val (nextDetector, flux, rms) = detector.step(frame.samples)
    val nextEnvelope = (envelope :+ flux).takeRight(settings.capacity)
    val nextLoudness = (loudness :+ rms).takeRight(settings.capacity)
    val n = frames + 1
    val nextBeat =
      if (n % settings.reestimateEvery != 0) beat
      else if (nextEnvelope.size < settings.minFrames) None
      else BeatTracker.estimate(settings, nextEnvelope, nextLoudness, frame.capturedAt, beat)
    BeatTracker(settings, nextDetector, nextEnvelope, nextLoudness, n, nextBeat)
  }
}

object BeatTracker {

  def initial(settings: TrackerSettings = TrackerSettings()): BeatTracker =
    BeatTracker(settings, OnsetDetector.initial(settings.window), Vector.empty, Vector.empty, 0L, None)

  /** Tempos near 120 BPM are more likely than very slow or very fast ones; this breaks ties between multiples. */
  private def prior(bpm: Double): Double = {
    val octaves = math.log(bpm / 120.0) / math.log(2)
    math.exp(-0.5 * octaves * octaves)
  }

  private def interpolate(e: Array[Double], index: Double): Double = {
    val i = math.floor(index).toInt
    val t = index - i
    if (i < 0 || i + 1 >= e.length) 0.0 else e(i) * (1 - t) + e(i + 1) * t
  }

  def estimate(
    s: TrackerSettings,
    envelope: Vector[Double],
    loudness: Vector[Double],
    now: FiniteDuration,
    previous: Option[BeatState]
  ): Option[BeatState] = {
    val recentLoudness = loudness.takeRight((2 * s.frameRate).toInt)
    val quiet = recentLoudness.isEmpty || recentLoudness.sum / recentLoudness.size < s.silenceRms
    val raw = envelope.toArray
    val env = Array.tabulate(raw.length) { i =>
      0.25 * raw(math.max(0, i - 1)) + 0.5 * raw(i) + 0.25 * raw(math.min(raw.length - 1, i + 1))
    }
    val mean = env.sum / env.length
    val spread = math.sqrt(env.map(v => (v - mean) * (v - mean)).sum / env.length)
    if (quiet || spread <= 1e-9) None
    else {
      val last = env.length - 1

      /** Average onset strength on a beat grid with this period that ends `offset` frames before the latest frame. */
      def gridMean(period: Double, offset: Double): (Double, Int) = {
        var sum = 0.0
        var k = 0
        var position = last - offset
        while (position >= 0) {
          sum += interpolate(env, position)
          k += 1
          position -= period
        }
        (if (k == 0) 0.0 else sum / k, k)
      }

      def bestOffset(period: Double): (Double, Double, Int) = {
        val offsets = Iterator.iterate(0.0)(_ + 0.5).takeWhile(_ < period)
        offsets.map { o => val (m, k) = gridMean(period, o); (o, m, k) }.maxBy(_._2)
      }

      /**
       * How well a beat grid explains the onsets: how far above average its beats are, weighted by how many hits it
       * covers (so a grid isn't rewarded for skipping every other beat) and by how plausible its tempo is.
       */
      def score(period: Double): Double = {
        val (_, m, k) = bestOffset(period)
        (m - mean) / spread * math.sqrt(k.toDouble) * prior(60 * s.frameRate / period)
      }

      val minPeriod = 60 * s.frameRate / s.maxBpm
      val maxPeriod = math.min(env.length / 2.0, 60 * s.frameRate / s.minBpm)
      val candidates = Iterator.iterate(minPeriod)(_ + 0.1).takeWhile(_ <= maxPeriod).toVector
      val period = candidates.maxBy(score)
      val measuredBpm = 60 * s.frameRate / period
      val bpm = previous match {
        case Some(p) if math.abs(measuredBpm - p.bpm) / p.bpm < 0.04 => 0.7 * p.bpm + 0.3 * measuredBpm
        case _                                                       => measuredBpm
      }
      val (offset, _, _) = bestOffset(60 * s.frameRate / bpm)
      val beatAt = now - (offset * s.hopDuration.toNanos).toLong.nanos
      Some(BeatState(bpm, beatAt, now))
    }
  }
}
