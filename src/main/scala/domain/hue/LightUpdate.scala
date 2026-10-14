package domain.hue

import domain.engine.RGB
import scodec.*
import scodec.bits.*
import scodec.codecs.*

case class LightUpdate(id: Int, color: RGB)
object LightUpdate {
  given Codec[LightUpdate] =
    (uint8 :: Codec[RGB]).as[LightUpdate]
}
