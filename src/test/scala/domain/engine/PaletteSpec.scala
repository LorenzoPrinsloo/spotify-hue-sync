package domain.engine

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import scala.util.Random
import testkit.Colours

class PaletteSpec extends ScalaCheckSuite {

  private def generate(mode: PaletteType, seed: Long): Option[Palette] =
    Palette.random(mode)(using new Random(seed))

  private val expectedSizes: Map[PaletteType, Int] = Map(
    PaletteType.Analogous -> 3,
    PaletteType.Complementary -> 2,
    PaletteType.Monochromatic -> 3,
    PaletteType.Triadic -> 3,
    PaletteType.Tetradic -> 4,
    PaletteType.AnalogousComplementary(Order.Flat) -> 6,
    PaletteType.AnalogousComplementary(Order.Interspersed) -> 6
  )

  property("every palette type produces a palette of the expected size") {
    forAll { (seed: Long) =>
      expectedSizes.forall((mode, size) => generate(mode, seed).map(_.colors.size) == Some(size))
    }
  }

  test("every named palette type has an expected size") {
    assertEquals(PaletteType.names.values.toSet, expectedSizes.keySet)
  }

  property("the same seed always produces the same palette") {
    forAll { (seed: Long) =>
      expectedSizes.keys.forall(mode => generate(mode, seed) == generate(mode, seed))
    }
  }

  property("complementary palettes pair a colour with its complement") {
    forAll { (seed: Long) =>
      generate(PaletteType.Complementary, seed).exists(p => p.colors.head.compliment == Some(p.colors(1)))
    }
  }

  property("palettes built from a base colour start from that colour") {
    forAll { (seed: Long) =>
      val base = RGB.random(using new Random(seed))
      generate(PaletteType.Triadic, seed).map(_.colors) == Some(base.triadic.toVector) &&
      generate(PaletteType.Tetradic, seed).map(_.colors) == Some(base.tetradic.toVector) &&
      generate(PaletteType.Monochromatic, seed).map(_.colors) == Some(base.monochromatic.toVector)
    }
  }

  property("a flat palette lists the base triad, then the complement's triad") {
    forAll { (seed: Long) =>
      generate(PaletteType.AnalogousComplementary(Order.Flat), seed).exists(p => p.colors(1).compliment == Some(p.colors(4)))
    }
  }

  property("an interspersed palette alternates between the two triads") {
    forAll { (seed: Long) =>
      generate(PaletteType.AnalogousComplementary(Order.Interspersed), seed).exists(p => p.colors(2).compliment == Some(p.colors(3)))
    }
  }

  property("a random palette is the palette built from the seed's random base colour") {
    forAll { (seed: Long) =>
      expectedSizes.keys.forall { t =>
        Palette.random(t)(using new Random(seed)) == Palette.from(RGB.random(using new Random(seed)), t)
      }
    }
  }

  property("from builds each palette type around the base colour") {
    forAll(Colours.vivid) { base =>
      val comp = base.compliment.get
      val expected: Map[PaletteType, Vector[RGB]] = Map(
        PaletteType.Complementary -> Vector(base, comp),
        PaletteType.Analogous -> base.analogous.toVector,
        PaletteType.Monochromatic -> base.monochromatic.toVector,
        PaletteType.Triadic -> base.triadic.toVector,
        PaletteType.Tetradic -> base.tetradic.toVector,
        PaletteType.AnalogousComplementary(Order.Flat) -> (base.analogous ++ comp.analogous).toVector,
        PaletteType.AnalogousComplementary(Order.Interspersed) ->
          Vector(base.analogous, comp.analogous).transpose.flatten
      )
      expectedSizes.keys.forall(t => Palette.from(base, t).map(_.colors) == expected.get(t))
    }
  }

  property("getColor always returns a colour from the palette, for any index") {
    forAll { (seed: Long, index: Int) =>
      generate(PaletteType.Tetradic, seed).exists(p => p.colors.contains(p.getColor(index)))
    }
  }

  property("getColor is 0-based and wraps around the palette") {
    forAll { (seed: Long, index: Int) =>
      generate(PaletteType.Tetradic, seed).exists { p =>
        val size = p.colors.size
        p.getColor(index) == p.colors(Math.floorMod(index, size)) &&
        (!(index.toLong + size).isValidInt || p.getColor(index) == p.getColor(index + size))
      }
    }
  }

  test("light 0 gets the first colour and the palette repeats after the last") {
    val p = generate(PaletteType.Triadic, 7L).getOrElse(fail("no palette"))
    assertEquals(p.getColor(0), p.colors(0))
    assertEquals(p.getColor(2), p.colors(2))
    assertEquals(p.getColor(3), p.colors(0))
    assertEquals(p.getColor(-1), p.colors(2))
  }

  property("along with no shift is the light's own colour") {
    forAll { (seed: Long, index: Int) =>
      generate(PaletteType.Tetradic, seed).exists(p => p.along(index, 0) == p.getColor(index))
    }
  }

  property("along by whole colours lands on palette colours, stopping at either end") {
    forAll(Gen.choose(0L, 1000L), Gen.choose(-20, 20), Gen.choose(-6, 6)) { (seed, index, shift) =>
      generate(PaletteType.Tetradic, seed).exists { p =>
        val last = p.colors.size - 1
        p.along(index, shift) == p.colors(math.max(0, math.min(last, Math.floorMod(index, p.colors.size) + shift)))
      }
    }
  }

  test("along part of the way blends towards the neighbouring colour") {
    val p = generate(PaletteType.Analogous, 7L).getOrElse(fail("no palette"))
    assertEquals(p.along(1, 0.5), p.colors(1).turnTowards(p.colors(2), 0.5))
    assertEquals(p.along(1, -0.25), p.colors(0).turnTowards(p.colors(1), 0.75))
  }

  test("palette names parse case-insensitively and reject unknown names") {
    assertEquals(PaletteType.parse("Triadic"), Some(PaletteType.Triadic))
    assertEquals(PaletteType.parse(" analogous-complementary-interspersed "), Some(PaletteType.AnalogousComplementary(Order.Interspersed)))
    assertEquals(PaletteType.parse("analogous-complementary"), Some(PaletteType.AnalogousComplementary(Order.Flat)))
    assertEquals(PaletteType.parse("rainbow"), None)
  }
}
