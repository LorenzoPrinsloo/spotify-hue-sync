# Roadmap

What's still to do, roughly in priority order. Tick items off as they land.

## Fixes

- [ ] **Refresh the Spotify access token.** The token is never renewed, and `SPOTIFY_REFRESH_TOKEN` is loaded but never used. After about an hour Spotify starts rejecting polls, the app treats that as "nothing playing", and the lights fade to rest until a restart. Use the refresh token (and `SpotifyToken.isExpired`, which exists but isn't called) to get a new access token before it expires, or when a poll comes back `401`.
- [ ] **Keep certificate checks on for Spotify.** The HTTP client skips certificate verification so it can reach the Hue bridge, which has a self-signed certificate. Spotify calls share that client, so they skip verification too. Give the bridge its own client.
- [ ] **Fix the Hue error message.** When a Hue request fails, `HueClient` logs "Spotify Exchange Failed" and the request body instead of the response.
- [ ] **Fail fast on a bad Hue key.** If `HUE_PSK` isn't valid hex, `DtlsEmitter` silently falls back to an empty key, so the connection fails later with a confusing error. Validate it when the config loads.

## Sign-in

- [ ] **Replace the `sp_dc` cookie with the standard browser flow.** The app already logs the authorize URL and runs the callback server, so the cookie request can go. This also removes the most sensitive secret from the setup.
- [ ] **Remember the refresh token between runs,** so the browser sign-in is only needed once.

## Light show

- [ ] **Colours from the album cover.** `PlaybackState` already decodes `albumCoverUrl` but nothing uses it. Picking the base colour from the cover art would tie the lights to the music.
- [ ] **Align pulses to the downbeat.** Pulses follow the track's tempo but are counted from the start of the track, so they can be offset from the actual beats. Spotify's audio analysis has beat timestamps, if the app is allowed to use it.
- [ ] **Order the wave by position.** The bridge reports each channel's position in the room; sorting by it would make the wave travel left to right instead of by channel ID.

## Code health

- [ ] **Remove or use the leftover code:** `SyncFrame`, `Fader`, `GroupId` and `LightId` aren't used anywhere, and neither is `getTrackAnalysis` now that the tempo comes from audio features.
- [ ] **Test the rest of the clients.** `getTempo` and `getChannels` are tested against fake servers; `getPlaybackState`, `setStreaming` and the sign-in flow aren't yet.
- [ ] **Add CI:** a GitHub Actions workflow that runs `sbt test` on every push.
- [ ] **Package the app,** for example as a Docker image, so it can run on a home server or Raspberry Pi.

## Done

- [x] Pulse to the track's real tempo, with a configurable fallback and beats-per-pulse
- [x] Monochromatic, triadic and tetradic palettes
- [x] Palette type chosen in config
- [x] Number of lights read from the bridge
- [x] Fade to a resting colour when playback stops
- [x] Tuning values moved into validated config, with defaults in `reference.conf`
- [x] Brightness scaled in HSB so colours keep their hue at the peak
- [x] 0-based light indexing
- [x] Frame sequence numbers that count up and wrap

- [x] Secrets moved out of the repo into environment variables
- [x] Palette cache held in a `Ref` instead of a mutable map
- [x] Property-based tests for the colour theory, palettes, pulse, Hue packets and Spotify decoding
- [x] Architecture and how-it-works documentation, with generated diagrams
