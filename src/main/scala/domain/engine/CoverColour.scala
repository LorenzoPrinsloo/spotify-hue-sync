package domain.engine

import cats.Monoid
import cats.syntax.all.*

/**
 * How a song's base colour is read from its album cover's pixels. Vivid pixels are grouped by hue into `HueBins` bins;
 * a bin counts together with `WindowBins` neighbours on each side, wrapping round 0°/360°, so a colour lying across a
 * bin edge isn't split below `MinShare`.
 */
object CoverColour {

  /** The least share of all the cover's pixels a colour has to cover to be its base colour. */
  val MinShare: Double = 0.05

  /** Pixels less saturated than this are black, white or grey. */
  val MinSaturation: Double = 0.35

  /** Pixels darker than this are black. */
  val MinBrightness: Double = 0.25

  /** Hue bins round the colour wheel, each 360 / `HueBins` degrees wide; bin k starts at k * 360 / `HueBins`. */
  val HueBins: Int = 36

  /** Neighbouring bins on each side counted with a bin. */
  val WindowBins: Int = 1

  /** Whether a pixel is a colour rather than black, white or grey. */
  def isVivid(c: RGB): Boolean = {
    val hsb = c.hsb
    hsb(1) >= MinSaturation && hsb(2) >= MinBrightness
  }

  /** Saturation times brightness: how vivid a colour is. */
  def vividness(c: RGB): Double = {
    val hsb = c.hsb
    hsb(1).toDouble * hsb(2).toDouble
  }

  /** The hue bin a colour falls in, from 0 to `HueBins` - 1. */
  def hueBin(c: RGB): Int =
    Math.floorMod(math.floor(c.hsb(0).toDouble * HueBins).toInt, HueBins)

  private case class Sums(count: Int, sin: Double, cos: Double, saturation: Double, brightness: Double, vividness: Double)

  private object Sums {
    val zero: Sums = Sums(0, 0.0, 0.0, 0.0, 0.0, 0.0)

    given Monoid[Sums] = Monoid.instance(
      zero,
      (a, o) =>
        Sums(a.count + o.count, a.sin + o.sin, a.cos + o.cos, a.saturation + o.saturation, a.brightness + o.brightness, a.vividness + o.vividness)
    )

    def of(c: RGB): Sums = {
      val hsb = c.hsb
      val angle = hsb(0).toDouble * 2 * math.Pi
      Sums(1, math.sin(angle), math.cos(angle), hsb(1).toDouble, hsb(2).toDouble, hsb(1).toDouble * hsb(2).toDouble)
    }
  }

  /**
   * The mean colour of the most vivid window of bins covering at least `MinShare` of the pixels, or None when there's
   * no such window.
   */
  def base(pixels: Seq[RGB]): Option[RGB] = {
    val total = pixels.size
    val perBin = pixels.filter(isVivid).groupMapReduce(hueBin)(Sums.of)(_ |+| _)
    val windows = (0 until HueBins).map { k =>
      (-WindowBins to WindowBins).map(d => perBin.getOrElse(Math.floorMod(k + d, HueBins), Sums.zero)).toList.combineAll
    }
    windows
      .filter(w => w.count > 0 && w.count.toDouble / total >= MinShare)
      .maxByOption(w => (w.vividness / w.count, w.count))
      .flatMap { w =>
        val turns = math.atan2(w.sin, w.cos) / (2 * math.Pi)
        val hue = turns - math.floor(turns)
        val (r, g, b) = Hsb.toRgb(hue.toFloat, (w.saturation / w.count).toFloat, (w.brightness / w.count).toFloat)
        RGB.safe(r, g, b).map(_.withMinimums)
      }
  }
}
