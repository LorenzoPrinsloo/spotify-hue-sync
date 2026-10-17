# Roadmap

What's still to do, roughly in priority order. Tick items off as they land.

## Fixes

- [ ] **Keep certificate checks on for Spotify.** The HTTP client skips certificate verification so it can reach the Hue bridge, which has a self-signed certificate. Spotify calls share that client, so they skip verification too. Give the bridge its own client.

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
