package domain.engine

import domain.config.PulseSettings
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import testkit.Colours.pulseSettings

class PulseSpec extends ScalaCheckSuite {

  private val defaults = PulseSettings()

  property("the envelope always stays between 0 and 1") {
    forAll(pulseSettings, Gen.choose(-100.0, 100.0)) { (s, phase) =>
      val l = Pulse.level(phase, s)
      l >= 0 && l <= 1
    }
  }

  property("every pulse starts dark") {
    forAll(pulseSettings, Gen.choose(-50, 50)) { (s, n) =>
      Pulse.level(n.toDouble, s) == 0.0
    }
  }

  property("the envelope holds at full during the sustain") {
    forAll(pulseSettings.suchThat(_.sustain > 0.01), Gen.choose(0.0, 1.0)) { (s, t) =>
      Pulse.level(s.attack + t * s.sustain * 0.999, s) == 1.0
    }
  }

  property("the envelope rises through the attack") {
    forAll(pulseSettings, Gen.choose(0.0, 1.0), Gen.choose(0.0, 1.0)) { (s, a, b) =>
      val (lo, hi) = (math.min(a, b) * s.attack * 0.999, math.max(a, b) * s.attack * 0.999)
      Pulse.level(lo, s) <= Pulse.level(hi, s)
    }
  }

  property("the envelope falls through the decay") {
    forAll(pulseSettings, Gen.choose(0.0, 1.0), Gen.choose(0.0, 1.0)) { (s, a, b) =>
      val start = s.attack + s.sustain
      val (lo, hi) = (start + math.min(a, b) * (1 - start) * 0.999, start + math.max(a, b) * (1 - start) * 0.999)
      Pulse.level(lo, s) >= Pulse.level(hi, s)
    }
  }

  property("the envelope repeats every pulse") {
    forAll(pulseSettings, Gen.choose(0.0, 1.0), Gen.choose(-20, 20)) { (s, phase, n) =>
      math.abs(Pulse.level(phase, s) - Pulse.level(phase + n, s)) < 1e-9
    }
  }

  test("with the default settings: up in the first 20%, full until 30%, then a soft fade") {
    assertEqualsDouble(Pulse.level(0.1, defaults), 0.5, 1e-9)
    assertEqualsDouble(Pulse.level(0.25, defaults), 1.0, 1e-9)
    assertEqualsDouble(Pulse.level(0.65, defaults), 0.25, 1e-9)
  }

  property("timed on the beat, the pulse rises just before each beat and peaks on it") {
    forAll(pulseSettings, Gen.choose(-1000, 1000), Gen.choose(0.0, 0.999)) { (s, n, t) =>
      Pulse.onBeat(n.toDouble, s) > 1 - 1e-9 &&
        math.abs(Pulse.onBeat(n - s.attack * (1 - t), s) - t) < 1e-9
    }
  }

  property("brightness runs from the rest level at 0 to rest level plus boost at the peak") {
    forAll(pulseSettings) { s =>
      Pulse.brightness(0, s) == s.restLevel && math.abs(Pulse.brightness(1, s) - (s.restLevel + s.peakBoost)) < 1e-12
    }
  }
}
