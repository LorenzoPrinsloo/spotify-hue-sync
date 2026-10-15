package beat

import scala.concurrent.duration.*

/** One block of captured audio, mixed to mono, and when it was captured (monotonic clock). */
case class AudioFrame(samples: Array[Float], capturedAt: FiniteDuration)

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

/** A moment that stood out, and how much: `strength` runs from 0 (barely) to 1 (as big as it gets). */
case class Hit(at: FiniteDuration, strength: Double)

/**
 * What the music is doing right now.
 *
 * `energy` and `treble` compare the last fraction of a second with the track's usual level, so 1.0 means typical,
 * below 1 quieter or duller, above 1 louder or brighter. `hit` is the latest big hit (a crash or a drop), `sparkle`
 * the latest treble onset (hi-hats, cymbals), and `sparkles` counts them, so each can land on a different light.
 * `pitch` is how high the vocals, leads and chords sit compared with usual for the track, from -1 (well below) to 1
 * (well above). `groove` is the bar's kick pattern (see [[Groove]]), empty without a beat.
 */
case class AudioReading(
  beat: Option[BeatState],
  energy: Double = 1.0,
  treble: Double = 1.0,
  hit: Option[Hit] = None,
  sparkle: Option[Hit] = None,
  sparkles: Long = 0,
  pitch: Double = 0.0,
  groove: Vector[Double] = Vector.empty
)

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

/**
 * Running averages of loudness, of treble activity (how much is happening in the high end: hi-hats, cymbals, the
 * bite of vocals) and of pitch: a short one for "now" and a long one for "usual". `pitchSpread` is how far pitch
 * usually strays from the usual.
 */
case class Levels(
  loudFast: Double = 0.0,
  loudNow: Double = 0.0,
  loudUsual: Double = 0.0,
  trebleNow: Double = 0.0,
  trebleUsual: Double = 0.0,
  heard: Long = 0,
  pitchNow: Double = 0.0,
  pitchUsual: Double = 0.0,
  pitchSpread: Double = 0.0
) {

  /** `trebleExcess` is how far a treble-led onset rose above the usual background, or 0. */
  def update(onset: Onset, trebleExcess: Double, s: TrackerSettings): Levels = {
    // Over a second or two: energy describes the section of the song, not each kick.
    val now = s.smoothing(1.5)
    // The usual level is learnt only from music, not silence, and starts as a plain average so the first
    // seconds of a track aren't compared with zero.
    val playing = onset.rms >= s.silenceRms
    val counted = if (playing) heard + 1 else heard
    val usual = if (playing) math.max(1.0 / counted, s.smoothing(20.0)) else 0.0
    val pitch = onset.pitch.filter(_ => playing)
    val nextPitchNow = pitch.fold(pitchNow)(p => pitchNow + (p - pitchNow) * math.max(1.0 / counted, s.smoothing(Levels.PitchSeconds)))
    val nextPitchUsual = pitch.fold(pitchUsual)(p => pitchUsual + (p - pitchUsual) * usual)
    Levels(
      loudFast = loudFast + (onset.rms - loudFast) * s.smoothing(0.05),
      loudNow = loudNow + (onset.rms - loudNow) * now,
      loudUsual = loudUsual + (onset.rms - loudUsual) * usual,
      trebleNow = trebleNow + (trebleExcess - trebleNow) * s.smoothing(0.6),
      trebleUsual = trebleUsual + (trebleExcess - trebleUsual) * usual,
      heard = counted,
      pitchNow = nextPitchNow,
      pitchUsual = nextPitchUsual,
      pitchSpread = pitch.fold(pitchSpread)(_ => pitchSpread + (math.abs(nextPitchNow - nextPitchUsual) - pitchSpread) * usual)
    )
  }

  def energy: Double = if (loudUsual <= 1e-9) 1.0 else loudNow / loudUsual

  // The small constant keeps the ratio sensible when a track has had almost no treble so far.
  def treble: Double = (trebleNow + 0.01) / (trebleUsual + 0.01)

  /** How far pitch is from usual, scaled so a typical move is about half way to the -1 or 1 it's capped at. */
  def pitch: Double = {
    val scale = math.max(Levels.PitchRange * pitchSpread, Levels.MinPitchRange)
    math.max(-1.0, math.min(1.0, (pitchNow - pitchUsual) / scale))
  }
}

object Levels {
  /** Pitch follows the music over about this long: long enough to ride over single drum hits, short enough for a melody. */
  val PitchSeconds: Double = 0.4

  /** Pitch this many typical moves away from usual counts as fully high or low. */
  val PitchRange: Double = 2.5

  /** Octaves that count as fully high or low at the least, so a track that hardly moves doesn't swing the colours. */
  val MinPitchRange: Double = 0.1
}

/**
 * Listens to live audio. Each hop of audio becomes one onset-strength value. A few times a second the tracker
 * tries every candidate beat grid over the last few seconds of onsets and keeps the one whose beats land on the
 * strongest onsets: that grid gives both the tempo and where the beats fall. Through quiet or unclear passages it
 * keeps the last confident beat for `holdSeconds`. Alongside the beat it follows the loudness and treble of the
 * music, and picks out big hits and treble sparkles.
 */
case class BeatTracker(
  settings: TrackerSettings,
  detector: OnsetDetector,
  envelope: Vector[Double],
  loudness: Vector[Double],
  frames: Long,
  beat: Option[BeatState],
  bass: Vector[Double] = Vector.empty,
  recent: Vector[BeatState] = Vector.empty,
  surges: Vector[Double] = Vector.empty,
  treble: Vector[Double] = Vector.empty,
  levels: Levels = Levels(),
  hit: Option[Hit] = None,
  sparkle: Option[Hit] = None,
  sparkles: Long = 0,
  groove: Option[Groove] = None
) {

  def reading: AudioReading =
    AudioReading(beat, levels.energy, levels.treble, hit, sparkle, sparkles, levels.pitch, groove.fold(Vector.empty)(_.pattern))

  def step(frame: AudioFrame): BeatTracker = {
    val s = settings
    val now = frame.capturedAt
    val (nextDetector, onset) = detector.step(frame.samples)
    val nextEnvelope = (envelope :+ onset.flux).takeRight(s.capacity)
    val nextBass = (bass :+ onset.bassFlux).takeRight(s.capacity)
    val nextLoudness = (loudness :+ onset.rms).takeRight(s.capacity)
    val n = frames + 1

    // A tempo that stays put is evidence in itself: real songs often score well under `minConfidence` while the tempo
    // doesn't budge. So the tracker locks on to a confident estimate, or to a few seconds of estimates that agree;
    // once locked, a weaker estimate that agrees on the tempo keeps the beat.
    val estimated =
      if (n % s.reestimateEvery != 0 || nextEnvelope.size < s.minFrames) None
      else BeatTracker.estimate(s, nextEnvelope, nextBass, nextLoudness, now, beat)
    val nextRecent = if (n % s.reestimateEvery != 0) recent else (recent ++ estimated).takeRight(s.steadyEstimates)
    val steady = BeatTracker.steady(nextRecent, s)
    val nextBeat =
      if (n % s.reestimateEvery != 0) beat
      else if (nextEnvelope.size < s.minFrames) None
      else
        estimated
          .filter(e => e.confidence >= s.minConfidence || steady || (e.confidence >= s.keepConfidence && beat.exists(BeatTracker.sameTempo(_, e))))
          .orElse(beat.filter(held => (now - held.updatedAt).toNanos / 1e9 <= s.holdSeconds))

    // Treble-led onsets are hi-hats and cymbals rather than the click of a kick, whose bass outweighs its treble.
    val trebleLed = onset.trebleFlux > BeatTracker.TrebleLed * onset.flux
    val background = BeatTracker.median(treble)
    val trebleExcess = if (trebleLed) math.max(0.0, onset.trebleFlux - 2 * background) else 0.0
    val nextLevels = levels.update(onset, trebleExcess, s)

    // Big hits and sparkles only count once a few seconds of music have set the bar. A big hit is a surge in
    // loudness over anything in those seconds: a crash or a drop lifts it for a while, a kick only for a moment.
    val settled = nextLoudness.size >= s.hitWindow && nextLoudness.takeRight(s.hitWindow).forall(_ >= s.silenceRms)
    val nextHit =
      BeatTracker.standsOut(nextLevels.loudFast, surges, s.hitRatio * surges.maxOption.getOrElse(0.0), hit, now, 400.millis)
        .filter(_ => settled)
        .orElse(hit)
    val newSparkle = BeatTracker
      .standsOut(onset.trebleFlux, treble, s.sparkleRatio * background, sparkle, now, 100.millis)
      .filter(_ => settled && trebleLed)

    // The kick pattern is learnt along the beat grid; a new lock starts a new one, since its beats are counted afresh.
    val nextGroove = nextBeat.map { b =>
      groove.filter(_ => beat.isDefined).fold(Groove.start(b.countAt(now)))(_.at(b.countAt(now), onset.kick))
    }

    copy(
      detector = nextDetector,
      envelope = nextEnvelope,
      bass = nextBass,
      recent = nextRecent,
      loudness = nextLoudness,
      frames = n,
      beat = nextBeat,
      surges = (surges :+ nextLevels.loudFast).takeRight(s.hitWindow),
      treble = (treble :+ onset.trebleFlux).takeRight(s.sparkleWindow),
      levels = nextLevels,
      hit = nextHit,
      sparkle = newSparkle.orElse(sparkle),
      sparkles = if (newSparkle.isDefined) sparkles + 1 else sparkles,
      groove = nextGroove
    )
  }
}

object BeatTracker {

  /** A kick's treble onset is well under its bass-weighted one; a hi-hat's is close to it or above. */
  val TrebleLed: Double = 0.65

  def initial(settings: TrackerSettings = TrackerSettings()): BeatTracker =
    BeatTracker(settings, OnsetDetector.initial(settings.window), Vector.empty, Vector.empty, 0L, None)

  /** Tempos within this fraction of each other count as the same, measured slightly differently. */
  val TempoTolerance: Double = 0.04

  def sameTempo(a: BeatState, b: BeatState): Boolean = math.abs(b.bpm - a.bpm) / a.bpm < TempoTolerance

  /** Whether the last `steadySeconds` of estimates all agree on the tempo, and are typically at least `keepConfidence`. */
  def steady(recent: Vector[BeatState], s: TrackerSettings): Boolean =
    recent.size >= s.steadyEstimates && {
      val middle = recent.sortBy(_.bpm).apply(recent.size / 2)
      recent.forall(sameTempo(middle, _)) && median(recent.map(_.confidence)) >= s.keepConfidence
    }

  def median(values: Vector[Double]): Double =
    if (values.isEmpty) 0.0 else values.sorted.apply(values.size / 2)

  /**
   * A new hit when `value` clears `bar`, and the previous one is at least `gap` ago. Its strength grows with how
   * far over the bar it is: just over is 0.5, twice the bar or more is 1.
   */
  def standsOut(
    value: Double,
    history: Vector[Double],
    bar: Double,
    previous: Option[Hit],
    now: FiniteDuration,
    gap: FiniteDuration
  ): Option[Hit] = {
    val clear = previous.forall(p => now - p.at >= gap)
    Option.when(history.nonEmpty && bar > 1e-9 && value > bar && clear)(Hit(now, math.min(1.0, 0.5 * value / bar)))
  }

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

  private def smoothed(values: Vector[Double]): Array[Double] = {
    val raw = values.toArray
    Array.tabulate(raw.length)(i => 0.25 * raw(math.max(0, i - 1)) + 0.5 * raw(i) + 0.25 * raw(math.min(raw.length - 1, i + 1)))
  }

  /** Average onset strength on a beat grid with this period that ends `offset` frames before the latest frame. */
  private def gridMean(env: Array[Double], period: Double, offset: Double): (Double, Int) = {
    var sum = 0.0
    var k = 0
    var position = env.length - 1 - offset
    while (position >= 0) {
      sum += interpolate(env, position)
      k += 1
      position -= period
    }
    (if (k == 0) 0.0 else sum / k, k)
  }

  private def bestOffset(env: Array[Double], period: Double): (Double, Double, Int) = {
    val offsets = Iterator.iterate(0.0)(_ + 0.5).takeWhile(_ < period)
    offsets.map { o => val (m, k) = gridMean(env, period, o); (o, m, k) }.maxBy(_._2)
  }

  /**
   * The tempo and how confident it is come from all the onsets, `envelope`. Where the beats fall comes from the low
   * end alone, `bass`: the kick lands on the beat, while hi-hats usually land between beats and can outweigh it.
   */
  def estimate(
    s: TrackerSettings,
    envelope: Vector[Double],
    bass: Vector[Double],
    loudness: Vector[Double],
    now: FiniteDuration,
    previous: Option[BeatState]
  ): Option[BeatState] = {
    val recentLoudness = loudness.takeRight((2 * s.frameRate).toInt)
    val quiet = recentLoudness.isEmpty || recentLoudness.sum / recentLoudness.size < s.silenceRms
    val env = smoothed(envelope)
    val mean = env.sum / env.length
    val spread = math.sqrt(env.map(v => (v - mean) * (v - mean)).sum / env.length)
    if (quiet || spread <= 1e-9) None
    else {

      /**
       * How well a beat grid explains the onsets: how far above average its beats are, weighted by how many hits it
       * covers (so a grid isn't rewarded for skipping every other beat) and by how plausible its tempo is.
       */
      def score(period: Double): Double = {
        val (_, m, k) = bestOffset(env, period)
        (m - mean) / spread * math.sqrt(k.toDouble) * prior(60 * s.frameRate / period)
      }

      val minPeriod = 60 * s.frameRate / s.maxBpm
      val maxPeriod = math.min(env.length / 2.0, 60 * s.frameRate / s.minBpm)
      val candidates = Iterator.iterate(minPeriod)(_ + 0.1).takeWhile(_ <= maxPeriod).toVector
      val period = candidates.maxBy(score)
      val measuredBpm = 60 * s.frameRate / period
      val bpm = previous match {
        case Some(p) if math.abs(measuredBpm - p.bpm) / p.bpm < TempoTolerance => 0.7 * p.bpm + 0.3 * measuredBpm
        case _                                                                  => measuredBpm
      }
      val beatFrames = 60 * s.frameRate / bpm
      val (_, gridLevel, _) = bestOffset(env, beatFrames)
      val confidence = (gridLevel - mean) / spread

      val low = smoothed(bass)
      val lowMean = low.sum / math.max(1, low.length)
      val phaseFrom = if (low.length == env.length && low.exists(_ > lowMean + 1e-9)) low else env
      val phaseMean = phaseFrom.sum / phaseFrom.length
      val (bestAt, bestLevel, _) = bestOffset(phaseFrom, beatFrames)
      // Once there's a beat, follow its grid (allowing for a little drift) and only move to another phase once that
      // has been stronger for a while: the beat and the off-beat are often close for a few bars, and flipping between
      // them throws the lights half a beat out.
      val (offset, doubt) = previous.filter(p => math.abs(bpm - p.bpm) / p.bpm < TempoTolerance) match {
        case Some(p) =>
          val sinceBeat = (now - p.beatAt).toNanos.toDouble / p.period.toNanos
          val continued = (sinceBeat - math.floor(sinceBeat)) * p.period.toNanos / s.hopDuration.toNanos
          val (followAt, followLevel) = (-4 to 4).map { step =>
            val o = continued + step * 0.5
            val wrapped = if (o < 0) o + beatFrames else if (o >= beatFrames) o - beatFrames else o
            (wrapped, gridMean(phaseFrom, beatFrames, wrapped)._1)
          }.maxBy(_._2)
          val phaseSpread = math.sqrt(phaseFrom.map(v => (v - phaseMean) * (v - phaseMean)).sum / phaseFrom.length)
          val memory = 1 - math.exp(-(now - p.updatedAt).toNanos / 1e9 / s.phaseMemorySeconds)
          val nextDoubt = p.doubt + ((bestLevel - followLevel) / math.max(phaseSpread, 1e-9) - p.doubt) * memory
          if (nextDoubt > s.phaseSwitch) (bestAt, 0.0) else (followAt, nextDoubt)
        case None => (bestAt, 0.0)
      }
      val beatAt = now - (offset * s.hopDuration.toNanos).toLong.nanos
      val number = previous.fold(0L)(p => p.number + math.round((beatAt - p.beatAt).toNanos.toDouble / p.period.toNanos))
      Some(BeatState(bpm, beatAt, now, confidence, doubt, number))
    }
  }
}
