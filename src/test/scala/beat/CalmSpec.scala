package beat

import domain.config.RhythmSettings
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll

class CalmSpec extends ScalaCheckSuite {

  private val eps = 1e-9

  private val slotPower: Gen[Double] = Gen.oneOf(Gen.const(0.0), Gen.choose(0.01, 1.0))
  private val patterns: Gen[Vector[Double]] = Gen.listOfN(Groove.Steps, slotPower).map(_.toVector)
  private val gaps: Gen[Int] = Gen.choose(1, 8)
  private val smoothings: Gen[Double] = Gen.choose(0.01, 0.9)

  private def lit(pattern: Vector[Double]): Vector[Int] = pattern.indices.filter(pattern(_) > 0).toVector

  private def pulses(
    source: Int => Vector[Double],
    from: Long,
    steps: Int,
    gap: Int
  ): Vector[Long] = (0 until steps).foldLeft((Option.empty[Long], Vector.empty[Long])) { case ((last, played), i) =>
    val step = from + i
    val shown = Calm.held(source(i), step, last, gap)
    if (shown(Groove.slot(step + 1)) > 0) (Some(step + 1), played :+ (step + 1)) else (last, played)
  }._2

  test("gapSteps is the fewest sixteenths that last the minimum gap") {
    List(
      (160L, 60.0, 1),
      (160L, 93.75, 1),
      (160L, 94.0, 2),
      (160L, 120.0, 2),
      (160L, 174.0, 2),
      (160L, 180.0, 2),
      (0L, 120.0, 1),
      (160L, 0.0, 1),
      (400L, 180.0, 5)
    ).foreach { (ms, bpm, expected) =>
      assertEquals(Calm.gapSteps(ms, bpm), expected, s"$ms ms at $bpm")
    }
  }

  property("threshold is 0 at and below t") {
    forAll(Gen.choose(0.0, 0.9), Gen.choose(0.0, 1.0)) { (t, f) =>
      Calm.threshold(t * f, t) == 0.0
    }
  }

  property("threshold is 1 at the loudest") {
    forAll(Gen.choose(0.0, 0.9))(t => math.abs(Calm.threshold(1.0, t) - 1.0) < eps)
  }

  property("threshold above t is (v - t) / (1 - t)") {
    forAll(Gen.choose(0.0, 0.9), Gen.choose(0.0, 1.0)) { (t, v) =>
      v <= t || math.abs(Calm.threshold(v, t) - (v - t) / (1 - t)) < eps
    }
  }

  property("threshold at 0 is the identity") {
    forAll(Gen.choose(0.0, 1.0))(v => Calm.threshold(v, 0.0) == v)
  }

  property("spaced never lights two slots closer than the gap, around the bar") {
    forAll(patterns, gaps) { (pattern, gap) =>
      val slots = lit(Calm.spaced(pattern, gap))
      slots.size < 2 || slots.indices.forall { j =>
        Math.floorMod(slots((j + 1) % slots.size) - slots(j), Groove.Steps) >= gap
      }
    }
  }

  property("spaced keeps each lit slot's value from the pattern's maximum nearby") {
    forAll(patterns, gaps) { (pattern, gap) =>
      val out = Calm.spaced(pattern, gap)
      lit(out).forall(slot => out(slot) == pattern(slot) || out(slot) <= pattern.max) &&
      (lit(pattern).isEmpty || out.max == pattern.max)
    }
  }

  property("spaced is the identity at gap 1") {
    forAll(patterns)(pattern => Calm.spaced(pattern, 1) == pattern)
  }

  property("spaced puts each group's maximum on its first slot") {
    forAll(Gen.choose(0, 5), Gen.choose(2, 4), Gen.choose(0.1, 1.0), Gen.choose(0.1, 1.0)) { (at, gap, a, b) =>
      val pattern = Vector.fill(Groove.Steps)(0.0).updated(at, a).updated(at + gap - 1, b)
      val out = Calm.spaced(pattern, gap)
      out(at) == math.max(a, b) && lit(out) == Vector(at)
    }
  }

  test("a pair across the bar line merges onto the slot before it") {
    val pattern = Vector.fill(Groove.Steps)(0.0).updated(15, 0.6).updated(0, 1.0)
    val expected = Vector.fill(Groove.Steps)(0.0).updated(15, 1.0)
    assertEquals(Calm.spaced(pattern, 2), expected)
  }

  property("held never plays two pulses closer than the gap") {
    forAll(Gen.listOfN(120, patterns), gaps, Gen.choose(0L, 100000L)) { (sequence, gap, from) =>
      val played = pulses(sequence.toVector, from, sequence.size, gap)
      played.zip(played.drop(1)).forall { (a, b) => b - a >= gap }
    }
  }

  property("held is the identity without a recent pulse") {
    forAll(patterns, gaps, Gen.choose(0L, 1000L), Gen.choose(0L, 50L)) { (pattern, gap, step, extra) =>
      Calm.held(pattern, step, None, gap) == pattern &&
      Calm.held(pattern, step + gap + extra, Some(step), gap) == pattern
    }
  }

  property("held keeps the slot just pulsed") {
    forAll(patterns, gaps, Gen.choose(0L, 1000L)) { (pattern, gap, step) =>
      Calm.held(pattern, step, Some(step), gap)(Groove.slot(step)) == pattern(Groove.slot(step))
    }
  }

  property("held plays a lone slot every bar") {
    forAll(Gen.choose(0, Groove.Steps - 1), gaps, Gen.choose(0L, 1000L)) { (slot, gap, from) =>
      val pattern = Vector.fill(Groove.Steps)(0.0).updated(slot, 1.0)
      val steps = 4 * Groove.Steps
      val expected = (from + 1 to from + steps).filter(s => Groove.slot(s) == slot).toVector
      pulses(_ => pattern, from, steps, gap) == expected
    }
  }

  property("follow returns the target at smoothing 0") {
    forAll(Gen.choose(0.0, 1.0), Gen.choose(0.0, 1.0), Gen.choose(0L, 64L)) { (current, target, n) =>
      Calm.follow(current, target, 0.0, n) == target
    }
  }

  property("follow moves by the allowed step, more only to come within the jitter, and ends within it") {
    forAll(Gen.choose(0.0, 1.0), Gen.choose(0.0, 1.0), smoothings) { (current, target, s) =>
      val allowed = math.max(Calm.MinStepPerBar, Calm.StepPerBar * (1 - s) / s)
      val before = math.abs(target - current)
      val after = math.abs(target - Calm.follow(current, target, s, Groove.Steps))
      val moved = before - after
      moved >= math.min(before, allowed) - eps &&
      moved <= math.max(allowed, before - Calm.Jitter) + eps &&
      after <= Calm.Jitter + eps
    }
  }

  property("follow never overshoots the target") {
    forAll(Gen.choose(0.0, 1.0), Gen.choose(0.0, 1.0), smoothings, Gen.choose(0L, 400L)) { (current, target, s, n) =>
      val next = Calm.follow(current, target, s, n)
      (next - target) * (current - target) >= -eps
    }
  }

  property("strength takes playing at once below the drop-out") {
    forAll(Gen.choose(0.0, 1.0), Gen.choose(0.0, Calm.DropOut - 0.001), smoothings) { (current, playing, s) =>
      Calm.strength(current, playing, s, Groove.Steps) == playing &&
      Calm.strength(Calm.DropOut - 0.01, current, s, Groove.Steps) == current
    }
  }

  property("strength follows playing above the drop-out") {
    forAll(Gen.choose(0.4, 1.0), Gen.choose(0.4, 1.0), smoothings) { (current, playing, s) =>
      Calm.strength(current, playing, s, Groove.Steps) == Calm.follow(current, playing, s, Groove.Steps)
    }
  }

  property("strength is playing at smoothing 0") {
    forAll(Gen.choose(0.0, 1.0), Gen.choose(0.0, 1.0))((current, playing) =>
      Calm.strength(current, playing, 0.0, Groove.Steps) == playing
    )
  }

  property("shown rests below the resting level only while smoothing") {
    forAll(Gen.choose(0.0, Calm.Resting - 0.001), smoothings) { (v, s) =>
      Calm.shown(v, s) == 0.0 && Calm.shown(v, 0.0) == v
    }
  }

  property("shown keeps a strength at or above the resting level") {
    forAll(Gen.choose(Calm.Resting, 1.0), smoothings)((v, s) => Calm.shown(v, s) == v)
  }

  property("pattern at no threshold, gap or smoothing is the levels scaled by the strength") {
    forAll(patterns, Gen.choose(0.0, 1.0)) { (levels, strength) =>
      val out = Calm.pattern(levels, strength, RhythmSettings(0, 0.0, 0.0), 120)
      out.zip(levels).forall { (o, l) => math.abs(o - l * strength) < eps }
    }
  }
}
