package beat

import domain.config.{LightShowConfig, PulseSettings, ReactiveSettings, RhythmSettings}
import domain.engine.{Effects, LightRole, Pulse}
import munit.FunSuite
import scala.concurrent.duration.*
import scala.util.Random

class RhythmLightsSpec extends FunSuite {

  override val munitTimeout: Duration = 5.minutes

  private val pulse = PulseSettings()
  private val rhythmLights = ReactiveSettings(roles = List(LightRole.Rhythm))
  private val uncalmed = RhythmSettings(0, 0, 0)
  private val firstBeat = 0.25
  private val hop = Synth.settings.hop / Synth.settings.sampleRate

  private case class Hop(at: Double, reading: AudioReading, groove: Option[Groove], beats: Option[Double], level: Double, brightness: Double)

  private def play(audio: Array[Float], tracker: TrackerSettings = TrackerSettings()): Vector[Hop] =
    Synth.frames(audio).scanLeft(BeatTracker.initial(tracker))(_.step(_)).drop(1).map { t =>
      val at = t.frames * tracker.hop / tracker.sampleRate
      val reading = t.reading
      val now = at.seconds
      val beats = reading.beat.map(_.countAt(now))
      val (level, brightness) = beats.fold((0.0, 0.0)) { b =>
        (Pulse.rhythm(reading.groove, b, pulse), Effects.at(now, reading, 1, rhythmLights).rhythmLight(b, pulse))
      }
      Hop(at, reading, t.groove, beats, level, brightness)
    }.toVector

  private def locked(hops: Vector[Hop]): Vector[Hop] = hops.dropWhile(_.beats.isEmpty)

  /**
   * The first hop of each plateau that tops its neighbours, is above `above`, and stands at least `prominence` over the
   * lowest level since the last such peak: a pulse the eye sees, not a ripple from a pulse's strength changing.
   */
  private def peaks(hops: Vector[Hop], above: Double = 0.2, prominence: Double = 0.1): Vector[Double] = {
    val levels = hops.map(_.level)
    val tops = levels.indices.filter { i =>
      levels(i) > above && (i == 0 || levels(i - 1) < levels(i)) && levels.drop(i + 1).dropWhile(_ == levels(i)).headOption.forall(_ < levels(i))
    }
    tops.foldLeft((Vector.empty[Double], 0)) { case ((found, from), i) =>
      if (levels(i) - levels.slice(from, i + 1).min >= prominence) (found :+ hops(i).at, i) else (found, from)
    }._1
  }

  private def sixteenth(bpm: Double): Double = 15.0 / bpm
  private def stepAt(bpm: Double, step: Int): Double = firstBeat + step * sixteenth(bpm)
  private def bar(bpm: Double): Double = 16 * sixteenth(bpm)

  private def on(slots: Int*): Int => Double = {
    val set = slots.toSet
    step => if (set(step % 16)) 1.0 else 0.0
  }

  private val beatsOnly: Int => Double = step => if (step % 4 == 0) 1.0 else 0.0

  private def withPower(base: Int => Double, powers: Map[Int, Double]): Int => Double =
    step => if (base(step) > 0) powers.getOrElse(step % 16, 1.0) else 0.0

  private val bpm120 = 120.0
  private val singleKicks = Seq(4, 12)
  private val crowded = Seq(0, 1, 4, 8, 9, 12)
  private val acrossBarLine = Seq(0, 4, 8, 9, 12, 15)

  private lazy val crowdedAudio = Synth.pattern(30, bpm120, on(crowded*))._1
  private lazy val acrossAudio = Synth.pattern(30, bpm120, on(acrossBarLine*))._1
  private lazy val unevenPairs = Synth.pattern(30, bpm120, withPower(on(crowded*), Map(0 -> 0.7, 1 -> 1.0, 8 -> 0.7, 9 -> 1.0)))._1
  private lazy val weakSecondKicks = {
    val rnd = new Random(7)
    val seconds = List(1, 9).map(slot => slot -> Vector.fill(40)(0.25 + 0.2 * rnd.nextDouble())).toMap
    val kicks: Int => Double = step => {
      val slot = step % 16
      if (!crowded.contains(slot)) 0.0 else seconds.get(slot).fold(1.0)(_(step / 16 % 40))
    }
    Synth.pattern(30, bpm120, kicks)._1
  }

  private lazy val crowdedDefault = play(crowdedAudio)
  private lazy val crowdedUncalmed = play(crowdedAudio, TrackerSettings(rhythm = uncalmed))
  private lazy val acrossDefault = play(acrossAudio)
  private lazy val unevenDefault = play(unevenPairs)
  private lazy val weakDefault = play(weakSecondKicks)

  private def assertPeakAbove(hops: Vector[Hop], level: Double = 0.5): Unit =
    assert(hops.exists(_.level > level), s"no level above $level, highest ${hops.map(_.level).maxOption}")

  private def closestPeak(found: Vector[Double], at: Double): Double = found.minBy(p => math.abs(p - at))

  private val settledAfter = 12.0

  private def checkPairs(name: String, hops: Vector[Hop], first: Seq[Int]): Unit = {
    val run = locked(hops)
    assertPeakAbove(run)
    val found = peaks(run)
    val gaps = found.zip(found.drop(1)).map((a, b) => b - a)
    assert(gaps.forall(_ >= 0.16), s"$name: close peaks at ${found.zip(found.drop(1)).filter((a, b) => b - a < 0.16)}; first lock ${run.head.at}")
    val starts =
      Iterator.from(0).takeWhile(stepAt(bpm120, _) < 29).filter(s => first.contains(s % 16)).map(stepAt(bpm120, _)).filter(_ > settledAfter).toSeq
    assert(starts.nonEmpty)
    starts.foreach(t =>
      assert(math.abs(closestPeak(found, t) - t) <= 2 * hop, s"$name: no peak within two hops of the kick at $t, nearest ${closestPeak(found, t)}")
    )
  }

  test("kicks closer than the minimum gap play one pulse, on the first of them, at the defaults") {
    checkPairs("crowded", crowdedDefault, Seq(0, 8))
  }

  test("a pair across the bar line plays one pulse, on the first of them") {
    checkPairs("across the bar line", acrossDefault, Seq(15, 8))
  }

  test("a quieter second kick doesn't add a pulse close to the first") {
    checkPairs("weak second kicks", weakDefault, Seq(0, 8))
  }

  test("a pair's pulse is as strong as a single kick's whichever of the two is louder") {
    val run = locked(unevenDefault).filter(_.at > settledAfter)
    assertPeakAbove(run)
    def peakAround(slot: Int): Double =
      run.filter(h => math.abs(((h.at - stepAt(bpm120, slot)) % bar(bpm120) + bar(bpm120)) % bar(bpm120)) < 0.25).map(_.level).max
    val single = singleKicks.map(peakAround).max
    List(0, 8).foreach(slot => assert(math.abs(peakAround(slot) - single) <= 0.05 * single, s"slot $slot ${peakAround(slot)}, single $single"))
  }

  test("without calming, close kicks play separate pulses about a sixteenth apart") {
    val run = locked(crowdedUncalmed)
    assertPeakAbove(run)
    val found = peaks(run)
    assert(found.zip(found.drop(1)).exists((a, b) => math.abs(b - a - sixteenth(bpm120)) < 2 * hop), found.take(12).toString)
  }

  private val bpm174 = 174.0
  private val fast = TrackerSettings(minBpm = 120)
  private lazy val fastAudio = Synth.pattern(30, bpm174, on(0, 2, 10))._1
  private lazy val fastDefault = play(fastAudio, fast)
  private lazy val fastWideGap = play(fastAudio, fast.copy(rhythm = RhythmSettings(minGapMs = 200)))

  private def peaksPerBar(hops: Vector[Hop], bpm: Double, above: Double): Seq[Int] = {
    val found = peaks(hops, above)
    val first = Iterator.from(0).find(k => stepAt(bpm, 16 * k + 13) > settledAfter).get
    Iterator.from(first).takeWhile(k => stepAt(bpm, 16 * k + 29) < 29).map { k =>
      val from = stepAt(bpm, 16 * k + 13)
      found.count(p => p >= from && p < from + bar(bpm))
    }.toSeq
  }

  test("kicks a sixteenth and a half apart at 174 BPM are far enough apart to each play, at the defaults") {
    val run = locked(fastDefault)
    assertPeakAbove(run)
    assert(math.abs(run.last.reading.beat.get.bpm - bpm174) < 3, run.last.reading.beat.toString)
    val perBar = peaksPerBar(run, bpm174, 0.5)
    assert(perBar.nonEmpty)
    assert(perBar.forall(_ == 3), perBar.toString)
  }

  test("a wider gap at 174 BPM merges the two kicks that are a sixteenth and a half apart") {
    val run = locked(fastWideGap)
    assertPeakAbove(run)
    assert(math.abs(run.last.reading.beat.get.bpm - bpm174) < 3, run.last.reading.beat.toString)
    val perBar = peaksPerBar(run, bpm174, 0.5)
    assert(perBar.nonEmpty)
    assert(perBar.forall(_ == 2), perBar.toString)
  }

  private val bassNotes = Map(2 -> 0.4, 6 -> 0.45, 10 -> 0.5, 14 -> 0.55, 15 -> 0.6)
  private lazy val bassAudio = Synth.pattern(30, bpm120, beatsOnly, step => bassNotes.get(step % 16).fold(0.0)(_ * 1.0))._1
  private lazy val bassDefault = play(bassAudio)
  private lazy val bassUncalmed = play(bassAudio, TrackerSettings(rhythm = uncalmed))

  private def offBeats(groove: Vector[Double]): Vector[Double] = groove.indices.filter(_ % Groove.StepsPerBeat != 0).map(groove).toVector

  test("weak bass notes between the kicks stay dark at the defaults, with the kicks lit") {
    val run = locked(bassDefault)
    assertPeakAbove(run)
    val groove = run.last.reading.groove
    assert(offBeats(groove).forall(_ <= 0.02), groove.toString)
    assert(groove.indices.filter(_ % Groove.StepsPerBeat == 0).forall(groove(_) > 0.5), groove.toString)
  }

  test("the same bass notes light weakly without calming, each below a third of the kick") {
    val run = locked(bassUncalmed)
    assertPeakAbove(run)
    val groove = run.last.reading.groove
    val weak = offBeats(groove).filter(_ > 0.05)
    assert(weak.nonEmpty, groove.toString)
    assert(weak.forall(_ < 0.3), weak.toString)
  }

  private val barLoudness: Int => Double = {
    val rnd = new Random(11)
    val loudness = Vector.fill(30)(0.8 + 0.4 * rnd.nextDouble())
    loudness(_)
  }
  private lazy val loudnessAudio = Synth.pattern(60, bpm120, beatsOnly, barLoudness = barLoudness)._1
  private lazy val loudnessDefault = play(loudnessAudio)
  private lazy val loudnessUncalmed = play(loudnessAudio, TrackerSettings(rhythm = uncalmed))

  private def barPeaks(hops: Vector[Hop]): Seq[Double] = {
    val from = Iterator.from(0).find(k => stepAt(bpm120, 16 * k + 2) > 2 * settledAfter).get
    Iterator.from(from).takeWhile(k => stepAt(bpm120, 16 * k + 18) < 59).map { k =>
      val start = stepAt(bpm120, 16 * k + 2)
      hops.filter(h => h.at >= start && h.at < start + bar(bpm120)).map(_.brightness).max
    }.toSeq
  }

  private def steps(peaks: Seq[Double]): Seq[Double] = peaks.zip(peaks.drop(1)).map((a, b) => math.abs(a - b) / math.max(a, b))

  test("the rhythm lights' peak brightness moves less than 10% from one bar to the next while the bars' loudness varies, at the defaults") {
    val run = locked(loudnessDefault)
    assertPeakAbove(run)
    val change = steps(barPeaks(run))
    assert(change.nonEmpty)
    assert(change.forall(_ <= 0.1), change.toString)
  }

  test("without calming, the peak brightness moves more than 10% between some bars as their loudness varies") {
    val run = locked(loudnessUncalmed)
    assertPeakAbove(run)
    assert(steps(barPeaks(run)).exists(_ > 0.1), steps(barPeaks(run)).toString)
  }

  private val bpm128 = 128.0
  private val kicksBefore = 8
  private val dropBars = 5
  private lazy val dropOutAudio = {
    val kicks: Int => Double = step => if (step / 16 < kicksBefore || step / 16 >= kicksBefore + dropBars) beatsOnly(step) else 0.0
    Synth.pattern(36, bpm128, kicks)._1
  }
  private lazy val dropOutDefault = play(dropOutAudio)
  private lazy val dropOutUncalmed = play(dropOutAudio, TrackerSettings(rhythm = uncalmed))
  private val dropStart = stepAt(bpm128, 16 * kicksBefore)
  private val dropEnd = stepAt(bpm128, 16 * (kicksBefore + dropBars))

  test("the beat stays locked and the groove keeps learning through a drop-out of the kicks") {
    val run = locked(dropOutDefault)
    assert(run.forall(_.beats.isDefined))
    assert(run.forall(_.groove.isDefined))
    val learnt = run.flatMap(_.groove).map(_.learnt)
    assert(learnt.zip(learnt.drop(1)).forall((a, b) => b >= a), "the groove was restarted")
    assert(learnt.last > learnt.head)
    assert(dropEnd - dropStart < TrackerSettings().holdSeconds)
  }

  test("the rhythm lights rest from the fourth bar of a drop-out and are back within two bars of the kicks") {
    val run = locked(dropOutDefault)
    assertPeakAbove(run)
    assert(peaks(run.filter(h => h.at > dropStart - bar(bpm128) && h.at < dropStart), 0.5).nonEmpty, "not lit before the drop-out")
    val resting = run.filter(h => h.at >= dropStart + 3 * bar(bpm128) && h.at < dropEnd)
    assert(resting.nonEmpty)
    assert(resting.forall(_.level == 0.0), resting.map(_.level).max.toString)
    assert(peaks(run.filter(h => h.at >= dropEnd && h.at < dropEnd + 2 * bar(bpm128)), 0.5).nonEmpty, "not back within two bars")
  }

  test("with no calming, the rhythm lights show exactly the groove's pattern and the engine's own brightness, on every hop") {
    val syncopated = Synth.pattern(30, bpm120, on(0, 3, 6, 10, 12))._1
    val drums = Synth.drums(20, _ => 124)._1
    List("drums" -> drums, "syncopated" -> syncopated, "drop-out" -> dropOutAudio).foreach { (name, audio) =>
      val run = locked(play(audio, TrackerSettings(rhythm = uncalmed)))
      assert(run.nonEmpty, name)
      assert(run.exists(_.groove.exists(_.learnt >= Groove.Steps)), name)
      run.foreach { h =>
        val pattern = h.groove.fold(Vector.empty)(_.pattern)
        val now = h.at.seconds
        assertEquals(h.reading.groove, pattern, s"$name at ${h.at}")
        assertEquals(h.reading.rhythmEnergy, None, s"$name at ${h.at}")
        val effects = Effects.at(now, h.reading, 1, rhythmLights)
        assertEquals(h.brightness,
          Pulse.brightness(Pulse.rhythm(pattern, h.beats.getOrElse(fail(s"$name has no beats at ${h.at}")), pulse), pulse, effects.intensity),
          s"$name at ${h.at}")
      }
    }
  }

  private val midKick = Synth.pattern(30, bpm120, step => if (step % 4 == 0) 1.0 else if (step % 16 == 2) 0.5 else 0.0)._1

  private def midKickSlot(threshold: Double): Double = {
    val show = LightShowConfig(reactive = ReactiveSettings(rhythm = RhythmSettings(threshold = threshold)))
    val groove = Synth.readings(midKick, TrackerSettings.from(show)).last._2.groove
    offBeats(groove).max
  }

  test("a mid-strength kick lights at a threshold of 0 and stays dark at 0.9") {
    assert(midKickSlot(0) > 0.05)
    assertEquals(midKickSlot(0.9), 0.0)
  }
}
