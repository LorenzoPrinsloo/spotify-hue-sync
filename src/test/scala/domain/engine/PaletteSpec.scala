package domain.engine

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import scala.util.Random

class PaletteSpec extends ScalaCheckSuite {

  private def generate(mode: PaletteType, seed: Long): Option[Palette] =
    Palette.random(mode)(using new Random(seed))

  property("analogous palettes have three colours") {
    forAll { (seed: Long) =>
      generate(PaletteType.Analogous, seed).map(_.colors.size) == Some(3)
    }
  }

  property("complementary palettes pair a colour with its complement") {
    forAll { (seed: Long) =>
      generate(PaletteType.Complementary, seed).exists { p =>
        p.colors.size == 2 && p.colors.head.compliment == Some(p.colors(1))
      }
    }
  }

  property("analogous-complementary palettes have six colours in either order") {
    forAll { (seed: Long) =>
      Order.values.forall { order =>
        generate(PaletteType.AnalogousComplementary(order), seed).map(_.colors.size) == Some(6)
      }
    }
  }

  property("getColor always returns a colour from the palette, for any index") {
    forAll { (seed: Long, index: Int) =>
      generate(PaletteType.Analogous, seed).exists { p =>
        p.colors.contains(p.getColor(index))
      }
    }
  }

  property("getColor wraps around the palette") {
    forAll { (seed: Long, index: Int) =>
      generate(PaletteType.Analogous, seed).exists { p =>
        val size = p.colors.size
        !(index.toLong + size).isValidInt || p.getColor(index) == p.getColor(index + size)
      }
    }
  }

  test("palette types without an implementation produce no palette") {
    List(PaletteType.Monochromatic, PaletteType.Triadic, PaletteType.Tetradic).foreach { mode =>
      assertEquals(generate(mode, 42L), None)
    }
  }
}
