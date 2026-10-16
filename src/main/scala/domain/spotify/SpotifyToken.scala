package domain.spotify

import io.circe.Codec

import scala.concurrent.duration.*
import givens.circe.common.given Codec[FiniteDuration]

case class SpotifyToken(
  access_token: String,
  token_type: String,
  expires_in: FiniteDuration,
  refresh_token: Option[String],
  scope: String
) derives Codec.AsObject
