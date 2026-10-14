package testkit

import domain.config.PulseSettings
import domain.engine.RGB
import org.scalacheck.{Arbitrary, Gen}

object Colours {

  val rgb: Gen[RGB] = for {
    r <- Gen.choose(0, 255)
    g <- Gen.choose(0, 255)
    b <- Gen.choose(0, 255)
  } yield RGB.safe(r, g, b).get

  given Arbitrary[RGB] = Arbitrary(rgb)

  /** Colours vivid enough for their hue to survive rounding to whole RGB values. */
  val vivid: Gen[RGB] = rgb.suchThat(c => spread(c) >= 80)

  val pulseSettings: Gen[PulseSettings] = for {
    attack <- Gen.choose(0.01, 0.6)
    sustain <- Gen.choose(0.0, 0.98 - attack)
    curve <- Gen.choose(0.5, 4.0)
    rest <- Gen.choose(0.0, 0.5)
    boost <- Gen.choose(0.0, 3.0)
    stagger <- Gen.choose(0.0, 0.5)
  } yield PulseSettings(stagger, attack, sustain, curve, rest, boost)

  def spread(c: RGB): Int = c.toVector.max - c.toVector.min

  def hue(c: RGB): Double = c.hsb(0).toDouble
  def saturation(c: RGB): Double = c.hsb(1).toDouble
  def brightness(c: RGB): Double = c.hsb(2).toDouble

  /** Distance between two hues on the wheel, in turns (0.5 = opposite). */
  def hueDistance(a: Double, b: Double): Double = {
    val d = math.abs(a - b) % 1.0
    math.min(d, 1.0 - d)
  }

  def degrees(turns: Double): Double = turns * 360.0

  def close(a: RGB, b: RGB, tolerance: Int = 1): Boolean =
    a.toVector.zip(b.toVector).forall((x, y) => math.abs(x - y) <= tolerance)
}
