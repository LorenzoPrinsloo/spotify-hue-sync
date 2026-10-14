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
    case _ => None
  }

  extension (pal: Palette) {
    // 1 based circular index
    def getColor(index: Int): RGB = {
      val i = index % pal.colors.size - 1
      pal.colors(if (i < 0) i + pal.colors.size else i)
    }
  }
}
