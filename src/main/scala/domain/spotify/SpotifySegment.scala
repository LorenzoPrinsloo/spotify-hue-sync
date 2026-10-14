package domain.spotify

import io.circe.Codec

case class SpotifySegment(
   start: Double,
   duration: Double,
   confidence: Double,
   loudnessStart: Double,
   loudnessMaxTime: Double,
   loudnessMax: Double,
   loudnessEnd: Double,
   pitches: Vector[Double],
   timbre: Vector[Double]
  ) derives Codec.AsObject