package domain.hue

import domain.engine.RGB
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import scodec.Codec
import scodec.bits.ByteVector

class FrameSpec extends ScalaCheckSuite {

  private val rgb: Gen[RGB] = for {
    r <- Gen.choose(0, 255)
    g <- Gen.choose(0, 255)
    b <- Gen.choose(0, 255)
  } yield RGB.safe(r, g, b).get

  given Arbitrary[Frame] = Arbitrary(
    for {
      zoneId <- Gen.uuid.map(_.toString)
      n <- Gen.choose(0, 10)
      updates <- Gen.listOfN(n, Gen.zip(Gen.choose(0, 255), rgb).map(LightUpdate.apply.tupled))
    } yield Frame(zoneId, updates)
  )

  private def encode(frame: Frame): ByteVector = Codec[Frame].encode(frame).require.bytes

  property("a frame is a 52-byte header plus 7 bytes per light") {
    forAll { (frame: Frame) =>
      encode(frame).size == 52 + 7 * frame.updates.size
    }
  }

  property("every frame starts with the HueStream protocol header, version 2.0") {
    forAll { (frame: Frame) =>
      encode(frame).take(11) == ByteVector("HueStream".getBytes) ++ ByteVector(0x02, 0x00)
    }
  }

  property("frames round-trip through the codec") {
    forAll { (frame: Frame) =>
      Codec[Frame].decodeValue(Codec[Frame].encode(frame).require).toOption == Some(frame)
    }
  }

  test("a light update is its channel id followed by 16-bit big-endian colour values") {
    val update = LightUpdate(3, RGB.safe(255, 128, 1).get)
    val bytes = Codec[LightUpdate].encode(update).require.bytes
    assertEquals(bytes, ByteVector(0x03, 0xff, 0x00, 0x80, 0x00, 0x01, 0x00))
  }
}
