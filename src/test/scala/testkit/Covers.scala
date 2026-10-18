package testkit

import domain.engine.RGB

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

object Covers {

  /** A PNG of horizontal stripes, each colour taking its share of the height. */
  def png(stripes: (RGB, Double)*): Array[Byte] = png(100, 100, stripes*)

  def png(width: Int, height: Int, stripes: (RGB, Double)*): Array[Byte] = {
    val img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val total = stripes.map(_._2).sum
    val edges = stripes.scanLeft(0.0)(_ + _._2).tail.map(e => math.round(e / total * height).toInt)
    for (y <- 0 until height; x <- 0 until width) {
      val i = edges.indexWhere(y < _) match {
        case -1 => stripes.size - 1
        case k => k
      }
      val c = stripes(i)._1
      img.setRGB(x, y, (c.r.value << 16) | (c.g.value << 8) | c.b.value)
    }
    val out = new ByteArrayOutputStream()
    ImageIO.write(img, "png", out)
    out.toByteArray
  }
}
