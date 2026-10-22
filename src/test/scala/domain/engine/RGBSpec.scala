package domain.engine

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.{forAll, propBoolean}
import scala.util.Random
import scodec.Codec
import testkit.Colours.{*, given}

class RGBSpec extends ScalaCheckSuite {

  private def random(seed: Long): RGB = RGB.random(using new Random(seed)).get

  private val coverColour: Gen[RGB] = for {
    h <- Gen.choose(0f, 0.999f)
    s <- Gen.choose(0.35f, 1f)
    b <- Gen.choose(0.25f, 1f)
  } yield {
    val (r, g, bl) = Hsb.toRgb(h, s, b)
    RGB.safe(r, g, bl).get
  }

  private def meetsMinimums(c: RGB): Boolean =
    c.hsb(1) >= RGB.BaseSaturation && c.hsb(2) >= RGB.BaseBrightness

  property("a random colour is at least 60% saturated and 50% bright") {
    forAll { (seed: Long) => meetsMinimums(random(seed)) }
  }

  test("random colours reach every 10 degree band of the colour wheel") {
    val bins = (0L until 5000L).map(s => (hue(random(s * 0x9e3779b97f4a7c15L)) * 36).toInt % 36).toSet
    assertEquals(bins.size, 36)
  }

  property("the same seed always gives the same random colour") {
    forAll { (seed: Long) => RGB.random(using new Random(seed)) == RGB.random(using new Random(seed)) }
  }

  property("a colour that already meets the minimums is left unchanged") {
    forAll(rgb.suchThat(meetsMinimums)) { c => c.withMinimums == c }
  }

  property("a lifted cover colour meets the minimums, keeps its hue and is raised no further than needed") {
    forAll(coverColour) { c =>
      val l = c.withMinimums
      val lowS = saturation(c) < RGB.BaseSaturation
      val lowB = brightness(c) < RGB.BaseBrightness
      val tolB = 1.0 / 255 + 1e-6
      meetsMinimums(l) &&
      (spread(c) < 20 || hueDistance(hue(l), hue(c)) < 0.02) &&
      (if (lowS) saturation(l) - RGB.BaseSaturation < 0.01 else math.abs(saturation(l) - saturation(c)) < 0.02) &&
      (if (lowB) brightness(l) - RGB.BaseBrightness <= tolB else math.abs(brightness(l) - brightness(c)) <= tolB)
    }
  }

  test("lifting a colour whose plain conversion rounds just under the minimum nudges the saturation up") {
    val c = RGB.safe(52, 129, 55).get
    val (r, g, b) = Hsb.toRgb(c.hsb(0), RGB.BaseSaturation, c.hsb(2))
    assert(Hsb.fromRgb(r, g, b)(1) < RGB.BaseSaturation)
    assert(meetsMinimums(c.withMinimums))
  }

  property("safe accepts a colour only when every channel is within 0-255") {
    forAll { (r: Int, g: Int, b: Int) =>
      val inRange = Seq(r, g, b).forall(v => v >= 0 && v <= 255)
      RGB.safe(r, g, b).isDefined == inRange
    }
  }

  property("the complement of a colour's complement is the colour itself") {
    forAll { (c: RGB) => c.compliment.flatMap(_.compliment) == Some(c) }
  }

  property("a colour and its complement add up to white on every channel") {
    forAll { (c: RGB) => c.compliment.exists(o => c.toVector.zip(o.toVector).forall(_ + _ == 255)) }
  }

  property("a complement sits on the opposite side of the colour wheel") {
    forAll(rgb.suchThat(spread(_) >= 30)) { c =>
      c.compliment.exists(o => math.abs(hueDistance(hue(c), hue(o)) - 0.5) < 0.005)
    }
  }

  property("analogous returns three colours with the original in the middle") {
    forAll { (c: RGB) =>
      val colours = c.analogous
      colours.size == 3 && colours(1) == c
    }
  }

  property("analogous neighbours are 30 degrees either side of the base hue") {
    forAll(vivid) { c =>
      val List(left, _, right) = c.analogous: @unchecked
      math.abs(degrees(hueDistance(hue(left), hue(c))) - 30) < 2 &&
      math.abs(degrees(hueDistance(hue(right), hue(c))) - 30) < 2
    }
  }

  property("rotating the hue keeps saturation and brightness") {
    forAll(vivid, Gen.choose(-360, 360)) { (c, deg) =>
      val r = c.rotateHue(deg)
      math.abs(brightness(r) - brightness(c)) <= 1.0 / 255 + 1e-6 &&
      math.abs(saturation(r) - saturation(c)) < 0.02
    }
  }

  property("rotating by a full turn gives the same colour back") {
    forAll { (c: RGB) => close(c.rotateHue(360), c) }
  }

  property("triadic colours are 120 degrees apart") {
    forAll(vivid) { c =>
      val List(a, b, d) = c.triadic: @unchecked
      a == c &&
      math.abs(degrees(hueDistance(hue(a), hue(b))) - 120) < 2 &&
      math.abs(degrees(hueDistance(hue(b), hue(d))) - 120) < 2 &&
      math.abs(degrees(hueDistance(hue(d), hue(a))) - 120) < 2
    }
  }

  property("tetradic colours are 90 degrees apart around the wheel") {
    forAll(vivid) { c =>
      val colours = c.tetradic
      colours.size == 4 && colours.head == c &&
      colours.zip(colours.tail :+ colours.head).forall((x, y) => math.abs(degrees(hueDistance(hue(x), hue(y))) - 90) < 2)
    }
  }

  property("monochromatic colours share the hue at three increasing brightness levels") {
    forAll(vivid) { c =>
      val colours = c.monochromatic
      val levels = colours.map(brightness)
      colours.size == 3 &&
      levels.zip(List(0.35, 0.65, 1.0)).forall((got, want) => math.abs(got - want) <= 1.0 / 255 + 1e-6) &&
      colours.drop(1).forall(x => degrees(hueDistance(hue(x), hue(c))) < 3)
    }
  }

  property("scaling brightness keeps the hue, so bright colours don't wash out") {
    forAll(vivid, Gen.choose(1.0, 4.0)) { (c, factor) =>
      val scaled = c.scaleBrightness(factor)
      degrees(hueDistance(hue(scaled), hue(c))) < 2 &&
      math.abs(saturation(scaled) - saturation(c)) < 0.02 &&
      math.abs(brightness(scaled) - math.min(1.0, brightness(c) * factor)) <= 1.0 / 255 + 1e-6
    }
  }

  property("scaling brightness never pushes a channel past 255 or below 0") {
    forAll(rgb, Gen.choose(0.0, 10.0)) { (c, factor) =>
      c.scaleBrightness(factor).toVector.forall(v => v >= 0 && v <= 255)
    }
  }

  property("mix gives the first colour at 0, the second at 1, and stays between them") {
    forAll(rgb, rgb, Gen.choose(0.0, 1.0)) { (a, b, t) =>
      val m = a.mix(b, t)
      a.mix(b, 0) == a && a.mix(b, 1) == b &&
      m.toVector.zip(a.toVector.zip(b.toVector)).forall { case (v, (x, y)) => v >= math.min(x, y) && v <= math.max(x, y) }
    }
  }

  property("turning towards another colour gives this colour at 0 and the other at 1") {
    forAll(rgb, rgb) { (a, b) => a.turnTowards(b, 0) == a && a.turnTowards(b, 1) == b }
  }

  property("turning goes the short way round the colour wheel, keeping the colours in between as vivid as the ends") {
    forAll(vivid, vivid, Gen.choose(0.1, 0.9)) { (a, b, t) =>
      val apart = hueDistance(hue(a), hue(b))
      val between = a.turnTowards(b, t)
      apart > 0.45 || (
        math.abs(hueDistance(hue(a), hue(between)) - t * apart) < 0.02 &&
          math.abs(hueDistance(hue(between), hue(b)) - (1 - t) * apart) < 0.02 &&
          saturation(between) >= math.min(saturation(a), saturation(b)) - 0.02
      )
    }
  }

  property("fromHex reads back a colour written as #rrggbb") {
    forAll { (c: RGB) =>
      val hex = f"#${c.r.value}%02x${c.g.value}%02x${c.b.value}%02x"
      RGB.fromHex(hex) == Some(c) && RGB.fromHex(hex.drop(1).toUpperCase) == Some(c)
    }
  }

  test("fromHex rejects anything that isn't six hex digits") {
    List("", "#", "#12345", "#1234567", "#gg0000", "red").foreach(s => assertEquals(RGB.fromHex(s), None, s))
  }

  property("the wire codec round-trips every colour") {
    forAll { (c: RGB) =>
      val codec = summon[Codec[RGB]]
      codec.encode(c).flatMap(codec.decodeValue).toOption == Some(c)
    }
  }
}
