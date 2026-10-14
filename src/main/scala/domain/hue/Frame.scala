package domain.hue

import scodec.*
import scodec.bits.*
import scodec.codecs.*

case class Frame(zoneId: String, updates: List[LightUpdate])
object Frame {
  private val header: Codec[Unit] =
    constant(ByteVector("HueStream".getBytes)) ~>
      constant(hex"0200") ~> // Version
      uint8.unit(0) ~> // Sequence
      constant(hex"0000") ~> // Reserved
      constant(hex"00") ~> // RGB Mode
      constant(hex"00") // Reserved
    
  private val uuidCodec: Codec[String] = fixedSizeBytes(36, ascii)
  
  given frameCodec: Codec[Frame] =
  (header ~> uuidCodec :: list(Codec[LightUpdate])).as[Frame]
}


