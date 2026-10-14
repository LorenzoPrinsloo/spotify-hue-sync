package domain.hue.types

opaque type GroupId <: Int = Int
object GroupId:
  def apply(i: Int): GroupId = i
  extension (g: GroupId) def value: Int = g