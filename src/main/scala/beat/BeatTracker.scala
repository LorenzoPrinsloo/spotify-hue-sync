package beat

import scala.concurrent.duration.*

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
  groove: Option[Groove] = None,
  news: Vector[TempoNews] = Vector.empty
) {

  def reading: AudioReading =
    AudioReading(beat, levels.energy, levels.treble, hit, sparkle, sparkles, levels.pitch, groove.fold(Vector.empty)(_.pattern), news)

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
    val estimate =
      if (n % s.reestimateEvery != 0 || nextEnvelope.size < s.minFrames) None
      else BeatTracker.estimate(s, nextEnvelope, nextBass, nextLoudness, now, beat)
    val estimated = estimate.map(_.state)
    val nextRecent = if (n % s.reestimateEvery != 0) recent else (recent ++ estimated).takeRight(s.steadyEstimates)
    val steady = BeatTracker.steady(nextRecent, s)
    val nextBeat =
      if (n % s.reestimateEvery != 0) beat
      else if (nextEnvelope.size < s.minFrames) None
      else
        estimated
          .filter(e => e.confidence >= s.minConfidence || steady || (e.confidence >= s.keepConfidence && beat.exists(BeatTracker.sameTempo(_, e))))
          .orElse(beat.filter(held => (now - held.updatedAt).toNanos / 1e9 <= s.holdSeconds))

    val news = for {
      e <- estimate.toVector
      winner <- e.movedFrom
      accepted <- nextBeat if accepted eq e.state
      if !beat.exists(BeatTracker.sameTempo(_, accepted))
    } yield TempoNews.KickMoved(winner, accepted.bpm)

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
      groove = nextGroove,
      news = news
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

  /** Tempo ratios, such as 4:3 and 3:2, that a kick and other accents can sit at; half and double aren't among them. */
  private val RelatedRatios: Vector[Double] = Vector(2.0 / 3, 3.0 / 4, 4.0 / 3, 3.0 / 2)

  /** How many times better than the all-onset winner a related tempo must score on the kick to replace it. */
  private val KickMargin: Double = 1.1

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
    offsets.map { o =>
      val (m, k) = gridMean(env, period, o); (o, m, k)
    }.maxBy(_._2)
  }

  /** A tempo estimate, and the all-onset winner's bpm when the kick moved it to a related tempo. */
  case class Estimate(state: BeatState, movedFrom: Option[Double])

  /**
   * Accents or a bass line can make a related tempo win on all the onsets while the kick keeps another: the period of
   * the related tempo the kick clearly prefers to the `winner`, if there is one.
   */
  private def kickPreferred(low: Array[Double], lowMean: Double, winner: Double, minPeriod: Double, maxPeriod: Double): Option[Double] = {
    val lowSpread = math.sqrt(low.map(v => (v - lowMean) * (v - lowMean)).sum / low.length)
    def kickScore(p: Double): Double = {
      val (_, m, k) = bestOffset(low, p)
      (m - lowMean) / math.max(lowSpread, 1e-9) * math.sqrt(k.toDouble)
    }
    val winnerScore = kickScore(winner)
    RelatedRatios
      .map(winner / _)
      .filter(p => p >= minPeriod && p <= maxPeriod)
      .map(p => (p, kickScore(p)))
      .maxByOption(_._2)
      .filter((_, kick) => kick > 0 && kick > KickMargin * winnerScore)
      .map(_._1)
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
  ): Option[Estimate] = {
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
      val winner = candidates.maxBy(score)

      val low = smoothed(bass)
      val lowMean = low.sum / math.max(1, low.length)
      val lowUsable = low.length == env.length && low.exists(_ > lowMean + 1e-9)

      val kickMoved = if (lowUsable) kickPreferred(low, lowMean, winner, minPeriod, maxPeriod) else None
      val period = kickMoved.getOrElse(winner)
      val measuredBpm = 60 * s.frameRate / period
      val bpm = previous match {
        case Some(p) if math.abs(measuredBpm - p.bpm) / p.bpm < TempoTolerance => 0.7 * p.bpm + 0.3 * measuredBpm
        case _ => measuredBpm
      }
      val beatFrames = 60 * s.frameRate / bpm
      val (_, gridLevel, _) = bestOffset(env, beatFrames)
      val confidence = (gridLevel - mean) / spread

      val phaseFrom = if (lowUsable) low else env
      val phaseMean = phaseFrom.sum / phaseFrom.length
      val (bestAt, bestLevel, _) = bestOffset(phaseFrom, beatFrames)
      // Once there's a beat, follow its grid (allowing for a little drift) and only move to another phase once that
      // has been stronger for a while: the beat and the off-beat are often close for a few bars, and flipping between
      // them throws the lights half a beat out.
      val (offset, doubt) = previous.filter(p => math.abs(bpm - p.bpm) / p.bpm < TempoTolerance).fold((bestAt, 0.0)) { p =>
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
      }
      val beatAt = now - (offset * s.hopDuration.toNanos).toLong.nanos
      val number = previous.fold(0L)(p => p.number + math.round((beatAt - p.beatAt).toNanos.toDouble / p.period.toNanos))
      Some(Estimate(BeatState(bpm, beatAt, now, confidence, doubt, number), kickMoved.map(_ => 60 * s.frameRate / winner)))
    }
  }
}
