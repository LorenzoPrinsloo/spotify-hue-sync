package domain.spotify.types

opaque type TrackId <: String = String
object TrackId:
  def apply(s: String): TrackId = s
  extension (t: TrackId) def value: String = t
