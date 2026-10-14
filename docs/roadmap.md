# Roadmap

What's still to do, roughly in priority order. Tick items off as they land.

## Fixes

- [ ] **Refresh the Spotify access token.** The token is never renewed, and `SPOTIFY_REFRESH_TOKEN` is loaded but never used. After about an hour Spotify starts rejecting polls, the app treats that as "nothing playing", and the lights stop until a restart. Use the refresh token (and `SpotifyToken.isExpired`, which exists but isn't called) to get a new access token before it expires, or when a poll comes back `401`.
- [ ] **Keep certificate checks on for Spotify.** The HTTP client skips certificate verification so it can reach the Hue bridge, which has a self-signed certificate. Spotify calls share that client, so they skip verification too. Give the bridge its own client.
- [ ] **Fix the Hue error message.** When a Hue request fails, `HueClient` logs "Spotify Exchange Failed" and the request body instead of the response.
- [ ] **Fail fast on a bad Hue key.** If `HUE_PSK` isn't valid hex, `DtlsEmitter` silently falls back to an empty key, so the connection fails later with a confusing error. Validate it when the config loads.

## Sign-in

- [ ] **Replace the `sp_dc` cookie with the standard browser flow.** The app already logs the authorize URL and runs the callback server, so the cookie request can go. This also removes the most sensitive secret from the setup.
- [ ] **Remember the refresh token between runs,** so the browser sign-in is only needed once.

## Light show

- [ ] **Pulse to the track's real tempo.** Every track pulses at a fixed 20 BPM. The client already fetches and caches Spotify's track analysis, but the engine doesn't use it. Spotify restricted the audio-analysis endpoint for new apps in late 2024, so check whether this app can still call it. If not, tempo needs another source.
- [ ] **Implement the remaining palette types:** `Monochromatic`, `Triadic` and `Tetradic` are declared but return no palette.
- [ ] **Make the palette type configurable.** The engine always uses `Analogous`.
- [ ] **Colours from the album cover.** `PlaybackState` already decodes `albumCoverUrl` but nothing uses it. Picking the base colour from the cover art would tie the lights to the music.
- [ ] **Read the number of lights from the bridge.** Frames always address seven channels; the entertainment configuration lists the real ones.
- [ ] **Decide what happens on pause.** When playback stops, the renderer stops sending frames. Fading to a resting colour would look better than freezing on the last frame.
- [ ] **Move the tuning values into config:** the 150 ms sync offset, the 0.15-beat stagger, the attack and sustain lengths, and the brightness boost.
- [ ] **Avoid washing out at the peak.** The 2.65× boost clips bright channels at 255, which shifts colours towards white. Scaling brightness in HSB instead of multiplying RGB would keep the hue.
- [ ] **Reconsider 1-based light indexing.** `getColor` gives light 0 the last palette colour, which is surprising.
- [ ] **Increment the frame sequence number.** Every Hue packet is sent with sequence `0`.

## Code health

- [ ] **Remove or use the leftover types:** `SyncFrame`, `Fader`, `GroupId` and `LightId` aren't used anywhere.
- [ ] **Test the clients.** `SpotifyClient` and `HueClient` have no tests yet. http4s makes it easy to test them against a fake client.
- [ ] **Add CI:** a GitHub Actions workflow that runs `sbt test` on every push.
- [ ] **Package the app,** for example as a Docker image, so it can run on a home server or Raspberry Pi.

## Done

- [x] Secrets moved out of the repo into environment variables
- [x] Palette cache held in a `Ref` instead of a mutable map
- [x] Property-based tests for the colour theory, palettes, pulse, Hue packets and Spotify decoding
- [x] Architecture and how-it-works documentation, with generated diagrams
