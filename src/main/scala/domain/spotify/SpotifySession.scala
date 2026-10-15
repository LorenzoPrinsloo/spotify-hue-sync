package domain.spotify

/** A logged-in Spotify session: the access token sent with each request, and the refresh token that renews it. */
case class SpotifySession(accessToken: String, refreshToken: String)
