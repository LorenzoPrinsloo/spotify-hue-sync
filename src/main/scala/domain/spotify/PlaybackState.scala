package domain.spotify

import domain.spotify.types.TrackId
import io.circe.Decoder

import scala.concurrent.duration.{DurationLong, FiniteDuration}

case class PlaybackState(
  trackId: TrackId,
  progress: FiniteDuration,
  isPlaying: Boolean,
  albumCoverUrl: String
)
object PlaybackState {
  given Decoder[PlaybackState] = Decoder.instance { cursor =>
    val item = cursor.downField("item")
    
    for {
      trackId <- item.downField("id").as[String]
      progress <- cursor.downField("progress_ms").as[Long]
      isPlaying <- cursor.downField("is_playing").as[Boolean]
      albumCoverUrl <- item.downField("album").downField("images").downArray.downField("url").as[String]
    } yield PlaybackState(trackId = TrackId(trackId), progress = progress.millis, isPlaying = isPlaying, albumCoverUrl = albumCoverUrl)
  }
}