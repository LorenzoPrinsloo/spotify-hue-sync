package domain.spotify

import domain.spotify.types.TrackId
import io.circe.Decoder

import scala.concurrent.duration.{DurationLong, FiniteDuration}

/** `albumCoverUrl` is the largest cover Spotify lists, or None for an item without one (a local file, an episode). */
case class PlaybackState(
  trackId: TrackId,
  progress: FiniteDuration,
  isPlaying: Boolean,
  albumCoverUrl: Option[String]
)
object PlaybackState {
  given Decoder[PlaybackState] = Decoder.instance { cursor =>
    val item = cursor.downField("item")
    val albumCoverUrl = item.downField("album").downField("images").downArray.downField("url").focus.flatMap(_.asString)

    for {
      trackId <- item.downField("id").as[String]
      progress <- cursor.downField("progress_ms").as[Long]
      isPlaying <- cursor.downField("is_playing").as[Boolean]
    } yield PlaybackState(trackId = TrackId(trackId), progress = progress.millis, isPlaying = isPlaying, albumCoverUrl = albumCoverUrl)
  }
}
