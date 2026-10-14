package domain.hue

import domain.engine.RGB
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import scodec.Codec
import scodec.bits.ByteVector
import testkit.Colours.rgb

class FrameSpec extends ScalaCheckSuite {

  given Arbitrary[Frame] = Arbitrary(
    for {
      zoneId <- Gen.uuid.map(_.toString)
      n <- Gen.choose(0, 20)
      updates <- Gen.listOfN(n, Gen.zip(Gen.choose(0, 255), rgb).map(LightUpdate.apply.tupled))
      sequence <- Gen.choose(0, 255)
    } yield Frame(zoneId, updates, sequence)
  )

  private def encode(frame: Frame): ByteVector = Codec[Frame].encode(frame).require.bytes

  property("a frame is a 52-byte header plus 7 bytes per light") {
    forAll { (frame: Frame) => encode(frame).size == 52 + 7 * frame.updates.size }
  }

  property("every frame starts with the HueStream protocol header, version 2.0") {
    forAll { (frame: Frame) => encode(frame).take(11) == ByteVector("HueStream".getBytes) ++ ByteVector(0x02, 0x00) }
  }

  property("the sequence number is byte 11, followed by the reserved and colour-space bytes") {
    forAll { (frame: Frame) =>
      val bytes = encode(frame)
      (bytes(11) & 0xff) == frame.sequence && bytes.slice(12, 16) == ByteVector(0x00, 0x00, 0x00, 0x00)
    }
  }

  property("the zone id follows the header") {
    forAll { (frame: Frame) => encode(frame).slice(16, 52) == ByteVector(frame.zoneId.getBytes("US-ASCII")) }
  }

  property("frames round-trip through the codec, including the sequence number") {
    forAll { (frame: Frame) => Codec[Frame].decodeValue(Codec[Frame].encode(frame).require).toOption == Some(frame) }
  }

  test("sequence numbers past 255 wrap around") {
    val frame = Frame("00000000-0000-0000-0000-000000000000", Nil, 257)
    assertEquals(encode(frame)(11) & 0xff, 1)
  }

  test("a light update is its channel id followed by 16-bit big-endian colour values") {
    val update = LightUpdate(3, RGB.safe(255, 128, 1).get)
    assertEquals(Codec[LightUpdate].encode(update).require.bytes, ByteVector(0x03, 0xff, 0x00, 0x80, 0x00, 0x01, 0x00))
  }
}
