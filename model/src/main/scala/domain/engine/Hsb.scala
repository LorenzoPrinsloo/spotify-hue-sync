package domain.engine

/**
 * Conversion between RGB and hue, saturation and brightness, each from 0 to 1, in plain Scala so it runs in the
 * browser too. It gives exactly what `java.awt.Color` gives on the JVM.
 */
object Hsb {

  /** Hue, saturation and brightness of a colour with channels from 0 to 255. */
  def fromRgb(r: Int, g: Int, b: Int): Array[Float] = {
    val max = math.max(r, math.max(g, b))
    val min = math.min(r, math.min(g, b))
    val brightness = max / 255.0f
    val saturation = if (max == 0) 0.0f else (max - min).toFloat / max
    val hue =
      if (saturation == 0.0f) 0.0f
      else {
        val spread = (max - min).toFloat
        val redc = (max - r) / spread
        val greenc = (max - g) / spread
        val bluec = (max - b) / spread
        val sector =
          if (r == max) bluec - greenc
          else if (g == max) 2.0f + redc - bluec
          else 4.0f + greenc - redc
        val turns = sector / 6.0f
        if (turns < 0) turns + 1.0f else turns
      }
    Array(hue, saturation, brightness)
  }

  /** Channels from 0 to 255 of a colour with this hue, saturation and brightness. */
  def toRgb(hue: Float, saturation: Float, brightness: Float): (Int, Int, Int) = {
    def channel(v: Float): Int = (v * 255.0f + 0.5f).toInt
    if (saturation == 0.0f) {
      val grey = channel(brightness)
      (grey, grey, grey)
    } else {
      val h = (hue - math.floor(hue.toDouble).toFloat) * 6.0f
      val f = h - math.floor(h.toDouble).toFloat
      val p = brightness * (1.0f - saturation)
      val q = brightness * (1.0f - saturation * f)
      val t = brightness * (1.0f - saturation * (1.0f - f))
      h.toInt match {
        case 0 => (channel(brightness), channel(t), channel(p))
        case 1 => (channel(q), channel(brightness), channel(p))
        case 2 => (channel(p), channel(brightness), channel(t))
        case 3 => (channel(p), channel(q), channel(brightness))
        case 4 => (channel(t), channel(p), channel(brightness))
        case _ => (channel(brightness), channel(p), channel(q))
      }
    }
  }
}
