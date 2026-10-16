package beat

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

  test("a jump in the grid doesn't count the sixteenths skipped over") {
    val learnt = play(Groove.start(0), 8, kicksOn(Set(0, 4, 8, 12)), 0)
    val jumped = learnt.at(8 * Groove.BeatsPerBar + 2.5, 0.05)
    assertEquals(jumped.learnt, learnt.learnt + 1)
    assertEquals(jumped.step, Groove.stepAt(8 * Groove.BeatsPerBar + 2.5))
  }
}
