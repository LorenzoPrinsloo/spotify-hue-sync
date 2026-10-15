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

  /** Parses `#rrggbb` (the `#` is optional). */
  def fromHex(hex: String): Option[RGB] = {
    val digits = hex.stripPrefix("#")
    if (digits.length != 6 || !digits.forall(Character.digit(_, 16) >= 0)) None
    else safe(
      Integer.parseInt(digits.substring(0, 2), 16),
      Integer.parseInt(digits.substring(2, 4), 16),
      Integer.parseInt(digits.substring(4, 6), 16)
    )
  }

  private def fromColor(c: java.awt.Color): RGB =
    RGB(Refined.unsafeApply(c.getRed), Refined.unsafeApply(c.getGreen), Refined.unsafeApply(c.getBlue))

  private def channel(v: Double): ByteInt = Refined.unsafeApply(math.round(math.max(0.0, math.min(255.0, v))).toInt)

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
      List(c.rotateHue(-30), c, c.rotateHue(30))

    def monochromatic: List[RGB] =
      List(0.35, 0.65, 1.0).map(c.withBrightness)

    def triadic: List[RGB] =
      List(c, c.rotateHue(120), c.rotateHue(240))

    /** A square tetrad: four hues spaced 90 degrees apart. */
    def tetradic: List[RGB] =
      List(c, c.rotateHue(90), c.rotateHue(180), c.rotateHue(270))

    def hsb: Array[Float] = RGBtoHSB(c.r.value, c.g.value, c.b.value, null)

    def rotateHue(degrees: Int): RGB = {
      val Array(h, sat, b) = c.hsb
      val newHue = ((h + degrees / 360.0f) % 1.0f + 1.0f) % 1.0f
      fromColor(getHSBColor(newHue, sat, b))
    }

    /** The same hue and saturation at an absolute brightness in [0, 1]. */
    def withBrightness(brightness: Double): RGB = {
      val Array(h, sat, _) = c.hsb
      fromColor(getHSBColor(h, sat, math.max(0.0, math.min(1.0, brightness)).toFloat))
    }

    /** Multiplies brightness in HSB, so the hue and saturation stay the same; brightness is capped at 1. */
    def scaleBrightness(factor: Double): RGB =
      c.withBrightness(c.hsb(2) * factor)

    /**
     * Blend towards `other` round the colour wheel, the short way, with saturation and brightness in step: the colours
     * in between stay as vivid as the two ends, where `mix` would grey them. 0 gives this colour, 1 gives `other`.
     */
    def turnTowards(other: RGB, t: Double): RGB = {
      val k = math.max(0.0, math.min(1.0, t))
      if (k == 0.0) c
      else if (k == 1.0) other
      else {
        val Array(h1, s1, b1) = c.hsb
        val Array(h2, s2, b2) = other.hsb
        val turn = (h2 - h1 + 1.5f) % 1.0f - 0.5f
        val hue = ((h1 + turn * k.toFloat) % 1.0f + 1.0f) % 1.0f
        fromColor(getHSBColor(hue, (s1 + (s2 - s1) * k).toFloat, (b1 + (b2 - b1) * k).toFloat))
      }
    }

    /** Linear blend towards `other`; 0 gives this colour, 1 gives `other`. */
    def mix(other: RGB, t: Double): RGB = {
      val k = math.max(0.0, math.min(1.0, t))
      RGB(
        channel(c.r.value + (other.r.value - c.r.value) * k),
        channel(c.g.value + (other.g.value - c.g.value) * k),
        channel(c.b.value + (other.b.value - c.b.value) * k)
      )
    }
  }
}