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

  def random(mode: PaletteType)(using rnd: Random = Random): Option[Palette] = mode match {
    case Complementary => {
      for {
        base <- RGB.random
        compliment <- base.compliment
      } yield Palette(colors = Vector(base, compliment))
    }
    case Analogous => {
      RGB.random.map { base =>
        Palette(colors = base.analogous.toVector)
      }
    }
    case Monochromatic =>
      RGB.random.map(base => Palette(base.monochromatic.toVector))
    case Triadic =>
      RGB.random.map(base => Palette(base.triadic.toVector))
    case Tetradic =>
      RGB.random.map(base => Palette(base.tetradic.toVector))
    case AnalogousComplementary(order) => {
      for {
        base <- RGB.random
        compliment <- base.compliment
        orderedColors = order match {
          case Flat => (base.analogous ++ compliment.analogous).toVector
          case Interspersed => Vector(base.analogous, compliment.analogous).transpose.flatten
        }
      } yield Palette(colors = orderedColors)
    }
  }

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
