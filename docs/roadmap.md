# Roadmap

What's still to do, roughly in priority order. Tick items off as they land.

## Sign-in

- [x] **Replace the `sp_dc` cookie with the standard browser flow.** The app already logs the authorize URL and runs the callback server, so the cookie request can go. This also removes the most sensitive secret from the setup.
- [ ] **Remember the refresh token between runs,** so the browser sign-in is only needed once.

## Light show

- [ ] **Avoid related but wrong tempos.** The tracker checks the 2/3, 3/4, 4/3 and 3/2 neighbours of its winner against the low end, but still settles on the wrong tempo when the kick itself supports both, and on double or half time, which the kick check doesn't cover.
- [ ] **Find the downbeat.** Live beat detection lands every pulse on a beat, but doesn't know which beat starts the bar. Accenting beat one would make the show feel more musical.
- [ ] **Tune the audio offset automatically,** by measuring the round trip from capture to the lights instead of setting `audio.offset-ms` by hand.
- [ ] **Snares on lights of their own.** Rhythm lights follow the kick; a second group following the snare would add a backbeat. Snares share their range with vocals and guitars, so they're harder to pick out.
- [ ] **Colour per musical note.** Colour lights follow where the sound is centred, not which notes are played. Following notes would need a finer frequency analysis than the beat tracker's, below about 700 Hz, where neighbouring notes share a frequency bin.
- [ ] **Order the wave by position.** The bridge reports each channel's position in the room; sorting by it would make the wave travel left to right instead of by channel ID.

## Code health

- [ ] **Package the app,** for example as a Docker image, so it can run on a home server or Raspberry Pi.
