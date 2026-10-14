package domain.spotify

import io.circe.Codec

/**
 * Port of spotify-credentials.d.ts
 * These are the static credentials required to communicate with Spotify.
 */
case class SpotifyCredentials(
   clientId: String,
   clientSecret: String,
   refreshToken: String
 ) derives Codec.AsObject
