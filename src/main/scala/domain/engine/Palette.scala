package domain.engine

import cats.Show
import cats.instances.show
import scala.util.Random
import domain.engine.PaletteType.*
import domain.engine.Order.*
import cats.syntax.all.*

case class Palette(colors: Vector[RGB])
object Palette {
  given Show[Palette] = Show.show { p =>
    p.colors.map(_.show).mkString("\n")
  }

  /**
   * The palette of type `mode` built around `base`. `None` when the base has no complement, which the complementary and
   * analogous-complementary types need.
   */
  def from(base: RGB, mode: PaletteType): Option[Palette] = mode match {
    case Complementary => base.compliment.map(compliment => Palette(colors = Vector(base, compliment)))
    case Analogous => Some(Palette(colors = base.analogous.toVector))
    case Monochromatic => Some(Palette(base.monochromatic.toVector))
    case Triadic => Some(Palette(base.triadic.toVector))
    case Tetradic => Some(Palette(base.tetradic.toVector))
    case AnalogousComplementary(order) => {
      base.compliment.map { compliment =>
        val orderedColors = order match {
          case Flat => (base.analogous ++ compliment.analogous).toVector
          case Interspersed => Vector(base.analogous, compliment.analogous).transpose.flatten
        }
        Palette(colors = orderedColors)
      }
    }
  }

  /** A palette of type `mode` around a random base colour: one `RGB.random` draw, so a seed fixes the palette. */
  def random(mode: PaletteType)(using rnd: Random = Random): Option[Palette] =
    RGB.random.flatMap(from(_, mode))

  extension (pal: Palette) {

    /** 0-based circular index: light 0 gets the first colour, and the palette repeats after the last. */
    def getColor(index: Int): RGB =
      pal.colors(Math.floorMod(index, pal.colors.size))

    /**
     * The colour `shift` palette colours on from colour `index` (negative is back), blending between neighbouring
     * colours. It stops at either end of the palette rather than wrapping round.
     */
    def along(index: Int, shift: Double): RGB = {
      val last = pal.colors.size - 1
      val position = math.max(0.0, math.min(last.toDouble, Math.floorMod(index, pal.colors.size) + shift))
      val from = math.floor(position).toInt
      if (from >= last) pal.colors(last) else pal.colors(from).turnTowards(pal.colors(from + 1), position - from)
    }
  }
}
