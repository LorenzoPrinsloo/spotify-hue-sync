package domain.hue.types

opaque type LightId <: String = String
object LightId:
  def apply(s: String): LightId = s
  extension (l: LightId) def value: String = l
