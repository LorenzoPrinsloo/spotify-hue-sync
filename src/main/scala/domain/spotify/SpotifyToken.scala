package domain.spotify

import io.circe.Codec

import java.time.Instant
import scala.concurrent.duration.*
import givens.circe.common.given Codec[FiniteDuration]

case class SpotifyToken(
   access_token: String,
   token_type: String,
   expires_in: FiniteDuration,
   refresh_token: Option[String],
   scope: String
 ) derives Codec.AsObject:
  val createdAt: Instant = Instant.now()
  def isExpired(buffer: FiniteDuration = 1.minute): Boolean = {
    val expirationTime = createdAt.plusSeconds(expires_in.toSeconds)
    Instant.now().isAfter(expirationTime.minusSeconds(buffer.toSeconds))
  }
