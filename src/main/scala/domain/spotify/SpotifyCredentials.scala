package domain.spotify

import io.circe.Codec

/**
 * Port of spotify-credentials.d.ts
 * These are the static credentials required to communicate with Spotify.
 */
case class SpotifyCredentials(
  clientId: String,
  clientSecret: String,
  refreshToken: Option[String] = None
) derives Codec.AsObject {

  /** The refresh token to start from, if one is configured: `SPOTIFY_REFRESH_TOKEN=` left empty in `.env` counts as none. */
  def configuredRefreshToken: Option[String] = refreshToken.map(_.trim).filter(_.nonEmpty)
}
