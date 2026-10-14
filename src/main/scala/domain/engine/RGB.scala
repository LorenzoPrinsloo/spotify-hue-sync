package domain.engine

import cats.Show
import eu.timepit.refined.*
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto.*
import eu.timepit.refined.numeric.Interval
import scodec.Codec
import scodec.codecs.*
import java.awt.Color.getHSBColor
import java.awt.Color.RGBtoHSB
import scala.io.AnsiColor
import cats.syntax.all.*
import scala.util.Random
import RGB.ByteInt

case class RGB(r: ByteInt, g: ByteInt, b: ByteInt):
  def toVector: Vector[Int] = Vector(r, g, b)
object RGB {
  type ByteInt = Int Refined Interval.Closed[0, 255]
  given Codec[RGB] =
    (uint16 :: uint16 :: uint16).xmap(
      (r, g, b) => RGB(
        Refined.unsafeApply(r >> 8),
        Refined.unsafeApply(g >> 8),
        Refined.unsafeApply(b >> 8)
      ),
      rgb => (
        rgb.r.value << 8,
        rgb.g.value << 8,
        rgb.b.value << 8
      )
    )

  given Show[RGB] = Show.show { c =>
    val colorBlock = s"\u001b[48;2;${c.r.value};${c.g.value};${c.b.value}m      \u001b[0m"
    s"$colorBlock"
  }

  def safe(r: Int, g: Int, b: Int): Option[RGB] =
    for {
      rr <- refineV[Interval.Closed[0, 255]](r).toOption
      gg <- refineV[Interval.Closed[0, 255]](g).toOption
      bb <- refineV[Interval.Closed[0, 255]](b).toOption
    } yield RGB(rr, gg, bb)

  def random(using rnd: Random = Random): Option[RGB] =
    safe(
      rnd.nextInt(256),
      rnd.nextInt(256),
      rnd.nextInt(256)
    )

  extension (c: RGB) {
    def compliment: Option[RGB] = {
      val max = 255
      safe(
        max - c.r.value,
        max - c.g.value,
        max - c.b.value
      )
    }

    def analogous: List[RGB] =
      // Shift hue by -30 degrees and +30 degrees
      List(c.rotate(-30), c.some, c.rotate(30)).flatten

    private def rotate(degrees: Int): Option[RGB] =
      val hsb = RGBtoHSB(c.r.value, c.g.value, c.b.value, null)
      // Hue is 0.0 to 1.0, so 30 degrees is 30/360
      val newHue = (hsb(0) + (degrees / 360.0f) + 1.0f) % 1.0f
      val next = getHSBColor(newHue, hsb(1), hsb(2))

      RGB.safe(
        next.getRed,
        next.getGreen,
        next.getBlue
      )
  }
}