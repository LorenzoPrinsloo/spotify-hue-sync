package beat

import domain.config.RhythmSettings
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class GrooveSpec extends ScalaCheckSuite {

  private val readingsPerStep = 6

  /** `bars` bars of kick power along the grid from `from` beats, with `power(slot)` on each sixteenth of the bar. */
  private def play(groove: Groove, bars: Int, power: Int => Double, from: Double): Groove = (0 until bars * Groove.Steps * readingsPerStep).foldLeft(
    groove
  ) { (g, i) =>
    val beats = from + i.toDouble / (readingsPerStep * Groove.StepsPerBeat)
    g.at(beats, power(Groove.slot(Groove.stepAt(beats))))
  }

  private def kicksOn(slots: Set[Int]): Int => Double = slot => if (slots(slot)) 1.0 else 0.05

  test("until a full bar has been heard, a kick on every beat") {
    assertEquals(Groove.start(0).pattern, Groove.Prior)
    assertEquals(play(Groove.start(0), 0, kicksOn(Set(2)), 0).pattern, Groove.Prior)
    assertEquals(Groove.Prior.indices.filter(Groove.Prior(_) > 0).toList, List(0, 4, 8, 12))
  }

  test("learns where the kicks fall, wherever in the grid it starts") {
    List(0.0, 37.0, 1001.5).foreach { from =>
      val pattern = play(Groove.start(from), 8, kicksOn(Set(0, 8, 10)), from).pattern
      val strong = pattern.indices.filter(pattern(_) > 0.9).toSet
      assertEquals(strong, Set(0, 8, 10), s"from $from: $pattern")
      assert(pattern.indices.filterNot(strong).forall(pattern(_) < 0.1), s"from $from: $pattern")
    }
  }

  test("when the kicks stop, the pattern fades within a few bars") {
    val learnt = play(Groove.start(0), 8, kicksOn(Set(0, 4, 8, 12)), 0)
    val stopped = play(learnt, 3, _ => 0.05, 8 * Groove.BeatsPerBar)
    assert(learnt.pattern.max > 0.9, learnt.pattern.toString)
    assert(stopped.pattern.max < 0.25, stopped.pattern.toString)
  }

  property("the pattern always stays between 0 and 1") {
    forAll(Gen.listOfN(Groove.Steps, Gen.choose(0.0, 100.0)), Gen.choose(0, 6), Gen.choose(-50.0, 50.0)) { (powers, bars, from) =>
      play(Groove.start(from), bars, powers, from).pattern.forall(w => w >= 0 && w <= 1)
    }
  }

  private def playCalm(
    groove: Groove,
    bars: Int,
    power: Int => Double,
    from: Double,
    settings: RhythmSettings,
    energy: Double = 1.0,
    bpm: Double = 120.0
  ): Groove = (0 until bars * Groove.Steps * readingsPerStep).foldLeft(groove) { (g, i) =>
    val beats = from + i.toDouble / (readingsPerStep * Groove.StepsPerBeat)
    g.at(beats, power(Groove.slot(Groove.stepAt(beats))), energy, settings, bpm)
  }

  private def lightsEveryReading(bars: Int, power: Int => Double, settings: RhythmSettings): List[Groove] =
    (0 until bars * Groove.Steps * readingsPerStep).scanLeft(Groove.start(0)) { (g, i) =>
      val beats = i.toDouble / (readingsPerStep * Groove.StepsPerBeat)
      g.at(beats, power(Groove.slot(Groove.stepAt(beats))), 1.0, settings, 120.0)
    }.toList

  private val noCalm = RhythmSettings(0, 0.0, 0.0)

  property("with no calming the lights are the pattern on every reading, and there is no rhythm energy") {
    forAll(Gen.listOfN(Groove.Steps, Gen.choose(0.0, 100.0)), Gen.choose(1, 4)) { (powers, bars) =>
      val plain = (0 until bars * Groove.Steps * readingsPerStep).scanLeft((Groove.start(0), Groove.start(0))) { case ((calm, old), i) =>
        val beats = i.toDouble / (readingsPerStep * Groove.StepsPerBeat)
        val kick = powers(Groove.slot(Groove.stepAt(beats)))
        (calm.at(beats, kick, 0.7, noCalm, 120.0), old.at(beats, kick))
      }
      plain.forall((calm, old) => calm.lights == old.pattern && calm.rhythmEnergy.isEmpty)
    }
  }

  test("at a threshold, each slot lights by its level beyond the threshold, and the loudest is full") {
    val kicks = Map(0 -> 1.0, 4 -> 0.7, 8 -> 0.4, 12 -> 1.0)
    val power: Int => Double = slot => kicks.getOrElse(slot, 0.05)
    List(0.0, 0.3, 0.9).foreach { t =>
      val groove = playCalm(Groove.start(0), 12, power, 0, RhythmSettings(0, t, 0.0))
      val shown = Calm.shown(groove.calm.strength, 0.0)
      assertEquals(groove.lights, groove.levels.map(Calm.threshold(_, t) * shown), s"threshold $t")
      assert(shown > 0.99, s"threshold $t")
      assertEqualsDouble(groove.lights.max, shown, 1e-9, s"threshold $t")
      assertEqualsDouble(groove.levels.max, 1.0, 1e-9, s"threshold $t")
    }
    val mid = 8
    val strict = playCalm(Groove.start(0), 12, power, 0, RhythmSettings(0, 0.9, 0.0))
    val open = playCalm(Groove.start(0), 12, power, 0, RhythmSettings(0, 0.0, 0.0))
    assert(strict.levels(mid) > 0.3 && strict.levels(mid) < 0.7, strict.levels.toString)
    assertEquals(strict.lights(mid), 0.0)
    assert(open.lights(mid) > 0.0)
  }

  test("a lone lit slot is played in the hop that records its pulse, and the hop that records it doesn't dark it") {
    val settings = RhythmSettings(400, 0.0, 0.0)
    val recorded = lightsEveryReading(12, slot => if (slot == 0) 1.0 else 0.05, settings).drop(1).filter(g => g.learnt >= Groove.Steps)
    val hops = recorded.sliding(2).filter(pair => pair(0).calm.lastPulse != pair(1).calm.lastPulse).toList
    assert(hops.nonEmpty)
    hops.foreach { pair =>
      val g = pair(1)
      assertEquals(g.calm.lastPulse, Some(g.step))
      assert(g.lights(Groove.slot(g.step)) > 0.0)
    }
  }

  test("the plain kick on every beat is played until a bar is learnt") {
    val settings = RhythmSettings()
    assertEquals(Groove.start(0).lights, Groove.Prior)
    assertEquals(playCalm(Groove.start(0), 0, _ => 1.0, 0, settings).lights, Groove.Prior)
    assertEquals(playCalm(Groove.start(0), 0, _ => 1.0, 0, settings).rhythmEnergy, None)
    assertNotEquals(playCalm(Groove.start(0), 8, slot => if (slot == 0) 1.0 else 0.05, 0, settings).lights, Groove.Prior)
  }

  test("a fresh groove has a fresh calm state") {
    assertEquals(Groove.start(12.5).calm, Calm.State())
  }

  test("a jump in the grid doesn't count the sixteenths skipped over") {
    val learnt = play(Groove.start(0), 8, kicksOn(Set(0, 4, 8, 12)), 0)
    val jumped = learnt.at(8 * Groove.BeatsPerBar + 2.5, 0.05)
    assertEquals(jumped.learnt, learnt.learnt + 1)
    assertEquals(jumped.step, Groove.stepAt(8 * Groove.BeatsPerBar + 2.5))
  }
}
