package domain.engine

import java.awt.Color
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class HsbSpec extends ScalaCheckSuite {

  override def scalaCheckTestParameters = super.scalaCheckTestParameters.withMinSuccessfulTests(20000)

  private val channel = Gen.choose(0, 255)

  property("RGB to HSB gives exactly what java.awt gives") {
    forAll(channel, channel, channel) { (r, g, b) =>
      Hsb.fromRgb(r, g, b).toList == Color.RGBtoHSB(r, g, b, null).toList
    }
  }

  property("HSB to RGB gives exactly what java.awt gives") {
    forAll(Gen.choose(-2.0f, 3.0f), Gen.choose(0.0f, 1.0f), Gen.choose(0.0f, 1.0f)) { (h, s, b) =>
      val c = Color.getHSBColor(h, s, b)
      Hsb.toRgb(h, s, b) == (c.getRed, c.getGreen, c.getBlue)
    }
  }

  test("greys, black and white match java.awt both ways") {
    (0 to 255).foreach { v =>
      assertEquals(Hsb.fromRgb(v, v, v).toList, Color.RGBtoHSB(v, v, v, null).toList)
      val c = Color.getHSBColor(0.3f, 0.0f, v / 255.0f)
      assertEquals(Hsb.toRgb(0.3f, 0.0f, v / 255.0f), (c.getRed, c.getGreen, c.getBlue))
    }
  }
}
