package domain.hue

import scodec.*
import scodec.bits.*
import scodec.codecs.*

case class Frame(zoneId: String, updates: List[LightUpdate], sequence: Int = 0)
object Frame {
  private val uuidCodec: Codec[String] = fixedSizeBytes(36, ascii)

  given frameCodec: Codec[Frame] =
    (
      (constant(ByteVector("HueStream".getBytes)) ~> // Protocol name
        constant(hex"0200") ~> // Version 2.0
        uint8) :: // Sequence
        (constant(hex"0000") ~> // Reserved
          constant(hex"00") ~> // Colour space: RGB
          constant(hex"00") ~> // Reserved
          uuidCodec) ::
        list(Codec[LightUpdate])
    ).xmap(
      { (sequence, zoneId, updates) => Frame(zoneId, updates, sequence) },
      frame => (frame.sequence & 0xff, frame.zoneId, frame.updates)
    )
}
