# Roadmap

What's still to do, roughly in priority order. Tick items off as they land.

## Fixes

- [ ] **Keep certificate checks on for Spotify.** The HTTP client skips certificate verification so it can reach the Hue bridge, which has a self-signed certificate. Spotify calls share that client, so they skip verification too. Give the bridge its own client.
- [x] **Fail fast on a bad Hue key.** If `HUE_PSK` isn't valid hex, `DtlsEmitter` silently falls back to an empty key, so the connection fails later with a confusing error. Validate it when the config loads.

## Sign-in

- [ ] **Replace the `sp_dc` cookie with the standard browser flow.** The app already logs the authorize URL and runs the callback server, so the cookie request can go. This also removes the most sensitive secret from the setup.
- [ ] **Remember the refresh token between runs,** so the browser sign-in is only needed once.

## Light show

- [ ] **Colours from the album cover.** `PlaybackState` already decodes `albumCoverUrl` but nothing uses it. Picking the base colour from the cover art would tie the lights to the music.
- [ ] **Avoid related but wrong tempos.** The tracker sometimes settles on 4/3 of a song's tempo. Checking the candidates at simple ratios of the winner (2/3, 3/4, 4/3, 3/2) against the low end could settle it.
- [ ] **Find the downbeat.** Live beat detection lands every pulse on a beat, but doesn't know which beat starts the bar. Accenting beat one would make the show feel more musical.
- [ ] **Tune the audio offset automatically,** by measuring the round trip from capture to the lights instead of setting `audio.offset-ms` by hand.
- [ ] **Snares on lights of their own.** Rhythm lights follow the kick; a second group following the snare would add a backbeat. Snares share their range with vocals and guitars, so they're harder to pick out.
- [ ] **Colour per musical note.** Colour lights follow where the sound is centred, not which notes are played. Following notes would need a finer frequency analysis than the beat tracker's, below about 700 Hz, where neighbouring notes share a frequency bin.
- [ ] **Order the wave by position.** The bridge reports each channel's position in the room; sorting by it would make the wave travel left to right instead of by channel ID.

## Code health

- [ ] **Package the app,** for example as a Docker image, so it can run on a home server or Raspberry Pi.

## Done

- [x] Live beat detection from audio (BlackHole), with Spotify timing as the fallback
- [x] Lights react to the music: brightness follows the energy, big hits flash, the treble turns the palette and hi-hats sparkle
- [x] The live beat is held through breakdowns and short silences instead of dropping straight to Spotify timing
- [x] Once locked, weaker estimates that agree on the tempo keep the beat, so quieter sections of a song don't lose it
- [x] Tempo changes logged, with the tracker's confidence
- [x] Light roles: rhythm lights play the bar's kick pattern ahead on the beat grid, colour lights swell every other beat and move along the palette with the pitch
- [x] The Spotify access token is renewed when it expires, so the lights keep going past the first hour
- [x] CI: GitHub Actions lints with Scalafix and runs the tests on every push
- [x] A settings page to try out light-show settings while the app runs, taken up as each song starts
- [x] The entertainment area is chosen by name, and can be switched from the settings page
- [x] Test coverage measured with scoverage, and `sbt check` (and CI) fails below 90% of statements, 85% of branches, or 70% of any file's statements
- [x] The leftover code is gone, the Hue error message names the area and the bridge's answer, and the Spotify and Hue clients are tested end to end against fake servers
- [x] Beats land on the kick rather than on off-beat hi-hats, and the tracker no longer flips between the beat and the off-beat
- [x] Locks on once the tempo has held steady for a few seconds, so songs with a softer beat don't fall back to Spotify timing
- [x] Pulses peak on the beat instead of a fifth of a beat after it, and captured audio is timed by its sample count, so the lights no longer trail the music

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
