package domain.engine

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import testkit.Colours.*

class CoverColourSpec extends ScalaCheckSuite {

  private def pixel(hueDegrees: Double, saturation: Double = 0.9, brightness: Double = 0.9): RGB = {
    val raw = hueDegrees / 360.0
    val turns = raw - math.floor(raw)
    val (r, g, b) = Hsb.toRgb(turns.toFloat, saturation.toFloat, brightness.toFloat)
    RGB.safe(r, g, b).get
  }

  private val black = pixel(0, 0.0, 0.0)
  private val white = pixel(0, 0.0, 1.0)
  private val grey = pixel(0, 0.0, 0.5)

  private def neutral(n: Int): Vector[RGB] = Vector.tabulate(n)(i => Vector(black, white, grey)(i % 3))

  private def patch(n: Int, hues: Double*): Vector[RGB] = Vector.tabulate(n)(i => pixel(hues(i % hues.size)))

  private def baseHueDegrees(pixels: Seq[RGB]): Option[Double] =
    CoverColour.base(pixels).map(c => degrees(hue(c)))

  private def degreesApart(a: Double, b: Double): Double = degrees(hueDistance(a / 360.0, b / 360.0))

  private def isNear(pixels: Seq[RGB], target: Double, tolerance: Double): Boolean =
    baseHueDegrees(pixels).exists(degreesApart(_, target) <= tolerance)

  test("a cover colour paler than the base minimums is lifted to them, keeping its hue") {
    val pale = Vector.fill(100)(pixel(200.0, saturation = 0.4, brightness = 0.4))
    val base = CoverColour.base(pale).get
    assert(base.hsb(1) >= RGB.BaseSaturation && base.hsb(2) >= RGB.BaseBrightness)
    assert(isNear(pale, 200.0, 5.0))
  }

  private def cover(colour: RGB): Vector[RGB] = Vector.fill(80)(colour) ++ neutral(20)

  private def assertHue(base: RGB, degreesTarget: Double): Unit =
    assert(degreesApart(degrees(hue(base)), degreesTarget) <= 2.0, base.hex)

  test("a pastel cover colour gives its hue and brightness with saturation raised to the minimum") {
    val base = CoverColour.base(cover(pixel(200.0, 0.45, 0.95))).get
    assertHue(base, 200.0)
    assert(saturation(base) >= 0.6, base.hex)
    assert(saturation(base) <= 0.61, base.hex)
    assert(math.abs(brightness(base) - 0.95) <= 0.01, base.hex)
  }

  test("a dull cover colour gives its hue and saturation with brightness raised to the minimum") {
    val base = CoverColour.base(cover(pixel(30.0, 0.8, 0.35))).get
    assertHue(base, 30.0)
    assert(brightness(base) >= 0.5, base.hex)
    assert(brightness(base) <= 0.5 + 1.0 / 255, base.hex)
    assert(math.abs(saturation(base) - 0.8) <= 0.01, base.hex)
  }

  test("a cover colour low in both saturation and brightness is raised to both minimums and no further") {
    val base = CoverColour.base(cover(pixel(120.0, 0.4, 0.3))).get
    assertHue(base, 120.0)
    assert(saturation(base) >= 0.6 && saturation(base) <= 0.61, base.hex)
    assert(brightness(base) >= 0.5 && brightness(base) <= 0.5 + 1.0 / 255, base.hex)
  }

  test("a uniform red cover gives exactly that red") {
    val red = RGB.safe(220, 20, 20).get
    assertEquals(CoverColour.base(cover(red)), Some(red))
  }

  test("a uniform blue cover gives exactly that blue") {
    val blue = RGB.safe(20, 20, 220).get
    assertEquals(CoverColour.base(cover(blue)), Some(blue))
  }

  test("a mostly vivid red cover gives red") {
    assert(isNear(patch(80, 0.0) ++ neutral(20), 0.0, 10.0))
  }

  test("a mostly vivid blue cover gives blue") {
    assert(isNear(patch(80, 240.0) ++ neutral(20), 240.0, 10.0))
  }

  test("black, white and grey give no base") {
    assertEquals(CoverColour.base(neutral(100)), None)
  }

  test("an empty cover gives no base") {
    assertEquals(CoverColour.base(Vector.empty), None)
  }

  test("neutral pixels with 4% vivid give no base") {
    assertEquals(CoverColour.base(patch(4, 200.0) ++ neutral(96)), None)
  }

  test("neutral pixels with 6% vivid give that hue") {
    assert(isNear(patch(6, 200.0) ++ neutral(94), 200.0, 5.0))
  }

  test("a 6% red patch split across 356 and 4 degrees gives red") {
    assert(isNear(patch(6, 356.0, 4.0) ++ neutral(94), 0.0, 10.0))
  }

  test("a 6% patch split between 28 and 32 degrees gives about 30 degrees") {
    assert(isNear(patch(6, 28.0, 32.0) ++ neutral(94), 30.0, 10.0))
  }

  test("separate 10% patches at red and green give one of them, not a blend") {
    val pixels = patch(10, 0.0) ++ patch(10, 120.0) ++ neutral(80)
    assert(isNear(pixels, 0.0, 10.0) || isNear(pixels, 120.0, 10.0))
  }

  test("the more vivid of two qualifying patches wins") {
    val dull = Vector.fill(20)(pixel(0.0, 0.4, 0.4))
    val bright = Vector.fill(10)(pixel(120.0, 1.0, 1.0))
    assert(isNear(dull ++ bright ++ neutral(70), 120.0, 5.0))
  }

  test("pixels that are too dark or too grey aren't vivid") {
    assert(!CoverColour.isVivid(pixel(0.0, 1.0, 0.2)))
    assert(!CoverColour.isVivid(pixel(0.0, 0.3, 1.0)))
    assert(CoverColour.isVivid(pixel(0.0, 0.9, 0.9)))
  }

  test("hue bins are 10 degrees wide") {
    assertEquals(CoverColour.hueBin(pixel(5.0)), 0)
    assertEquals(CoverColour.hueBin(pixel(125.0)), 12)
    assertEquals(CoverColour.hueBin(pixel(355.0)), 35)
  }

  property("all-neutral input gives no base") {
    forAll(Gen.listOf(Gen.oneOf(black, white, grey))) { pixels =>
      CoverColour.base(pixels).isEmpty
    }
  }

  property("any base measures at least the base minimums") {
    forAll(Gen.choose(1, 60).flatMap(Gen.listOfN(_, rgb))) { pixels =>
      CoverColour.base(pixels).forall(base => saturation(base) >= 0.6 && brightness(base) >= 0.5)
    }
  }

  property("a base comes from a window of bins with at least the minimum share of vivid pixels") {
    forAll(Gen.choose(1, 60).flatMap(Gen.listOfN(_, rgb))) { pixels =>
      CoverColour.base(pixels).forall { base =>
        val bins = pixels.filter(CoverColour.isVivid).map(CoverColour.hueBin)
        (0 until CoverColour.HueBins).exists { k =>
          val window = (-CoverColour.WindowBins to CoverColour.WindowBins).map(d => Math.floorMod(k + d, CoverColour.HueBins)).toSet
          val share = bins.count(window.contains).toDouble / pixels.size
          val centre = 360.0 / CoverColour.HueBins * (k + 0.5)
          val halfSpan = 360.0 / CoverColour.HueBins * (CoverColour.WindowBins + 0.5)
          share >= CoverColour.MinShare && degreesApart(degrees(hue(base)), centre) <= halfSpan + 4.0
        }
      }
    }
  }

  property("rotating every hue by a multiple of 10 degrees rotates the base by the same amount") {
    val cluster = for {
      bin <- Gen.choose(0, CoverColour.HueBins - 1)
      offsets <- Gen.listOfN(20, Gen.choose(-3.0, 3.0))
      saturation <- Gen.choose(0.7, 1.0)
      brightness <- Gen.choose(0.7, 1.0)
    } yield (bin * 10.0 + 5.0, offsets, saturation, brightness)
    forAll(cluster, Gen.choose(0, 200), Gen.choose(1, 35)) { case ((centre, offsets, s, b), fillerSize, steps) =>
      val filler = neutral(fillerSize)
      val original = offsets.map(o => pixel(centre + o, s, b)).toVector ++ filler
      val rotated = offsets.map(o => pixel(centre + o + steps * 10.0, s, b)).toVector ++ filler
      val expected = degrees(hue(CoverColour.base(original).get)) + steps * 10.0
      degreesApart(degrees(hue(CoverColour.base(rotated).get)), expected) <= 4.0
    }
  }
}
