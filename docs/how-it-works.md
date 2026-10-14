# How it works

This page follows a frame from Spotify to your lights: authentication, the two loops that drive the app, the colour engine, the pulse animation, what happens on pause, and the Hue streaming protocol. Every tuning value mentioned here can be changed in config; see [Configuration](#7-configuration).

## 1. Signing in to Spotify

The app uses Spotify's authorization code flow.

```mermaid
sequenceDiagram
    participant App as SpotifyClient
    participant CB as Callback server :7070
    participant Acc as accounts.spotify.com

    App->>App: log the authorize URL
    App->>Acc: GET /authorize (with SPOTIFY_SP_DC cookie if set)
    Acc-->>CB: redirect to /callback?code=...
    CB->>App: complete Deferred with the code
    App->>Acc: POST /api/token (code + client credentials)
    Acc-->>App: access token
    App->>App: store token in Ref
```

- The first call to Spotify triggers the sign-in. The app logs the authorize URL; opening it in a browser completes the flow.
- If `SPOTIFY_SP_DC` is set, the app also requests the authorize URL with that session cookie, which can complete the consent step without a browser.
- The callback server listens only on `127.0.0.1:7070`, so it is not reachable from other machines.

## 2. Two loops: polling and rendering

`SyncEngine.run` merges two fs2 streams that share the latest playback state through a `SignallingRef`.

```mermaid
flowchart TB
    subgraph Poller[Poller: every polling-interval-ms]
        P1[GET /me/player] --> P2[PlaybackState plus the time it was received]
        P2 --> P3[(SignallingRef<br/>latest playback)]
    end

    subgraph Renderer[Renderer: every frame-interval-ms]
        R1[read latest playback] --> R2{playing?}
        R2 -- no --> R3[fade towards the resting colour]
        R2 -- yes --> R4[tempo and palette for this track]
        R4 --> R5[estimate song position]
        R5 --> R6[pulseFrame]
        R3 --> R7[number the frame]
        R6 --> R7
        R7 --> R8[DtlsEmitter.send]
    end

    P3 --> R1
```

- **Poller.** Asks Spotify what is playing every `polling-interval-ms` (1000 ms in the shipped config). Failed polls are logged and treated as "nothing playing", so a network blip doesn't stop the app.
- **Renderer.** Runs every `frame-interval-ms` (16 ms, about 60 times a second). Spotify only reports the song position once per poll, so the renderer estimates where the song is now:

  `position = progress_ms + (now - time the poll arrived) + sync-offset-ms`

  `sync-offset-ms` (−150 ms by default) nudges the lights to line up with the audio.
- The renderer always sends a frame, playing or not, and numbers each one (see [Streaming to Hue](#6-streaming-to-hue)).

## 3. The colour engine

### Colours

`RGB` holds three channels, each a refined `Int` between 0 and 255, so an invalid colour can't be constructed through `RGB.safe`. Colour operations convert to HSB (hue, saturation, brightness), where the colour wheel lives, and back to RGB.

### Colour theory

| Relationship | How it's computed | Result |
|---|---|---|
| **Analogous** | Rotate the hue 30° each way, keeping saturation and brightness | `[base - 30°, base, base + 30°]`: neighbours on the wheel |
| **Complementary** | Invert each RGB channel (`255 - value`) | The colour opposite on the wheel (hue + 180°). Brightness and saturation change too, so a dark colour's complement is light |
| **Triadic** | Rotate the hue by 120° and 240° | Three colours evenly spaced around the wheel |
| **Tetradic** | Rotate the hue by 90°, 180° and 270° | A square: four colours evenly spaced around the wheel |
| **Monochromatic** | Keep the hue and saturation, set brightness to 35%, 65% and 100% | Shades of a single hue |

Each relationship is checked by property tests, for example that triadic hues are 120° apart and that monochromatic shades share one hue.

On the colour wheel, an **analogous** palette is a tight cluster of neighbours, which gives a calm, cohesive look:

![Analogous palette on the colour wheel](images/palette-analogous.svg)

A **complementary** palette sits on opposite sides of the wheel, which gives the strongest contrast:

![Complementary palette on the colour wheel](images/palette-complementary.svg)

**Triadic** and **tetradic** palettes spread evenly around the wheel, which gives a vivid, varied look:

![Triadic palette on the colour wheel](images/palette-triadic.svg)

![Tetradic palette on the colour wheel](images/palette-tetradic.svg)

A **monochromatic** palette stays on one spoke of the wheel and varies only in brightness, which gives a subtle, moody look:

![Monochromatic palette on the colour wheel](images/palette-monochromatic.svg)

### Palettes

`Palette.random(mode)` picks a random base colour and builds a palette from it. Choose the type with `light-show.palette`:

| `light-show.palette` | `PaletteType` | Colours |
|---|---|---|
| `analogous` (default) | `Analogous` | `[base - 30°, base, base + 30°]` |
| `complementary` | `Complementary` | `[base, complement]` |
| `triadic` | `Triadic` | `[base, base + 120°, base + 240°]` |
| `tetradic` | `Tetradic` | `[base, base + 90°, base + 180°, base + 270°]` |
| `monochromatic` | `Monochromatic` | the base hue at 35%, 65% and 100% brightness |
| `analogous-complementary` | `AnalogousComplementary(Flat)` | the base triad, then the complement's triad |
| `analogous-complementary-interspersed` | `AnalogousComplementary(Interspersed)` | the two triads interleaved |

**Analogous-complementary** combines two of them: the base triad on one side of the wheel and the complement's triad opposite it. `Order` only changes the sequence, and so which lights get which colour:

![Analogous-complementary palette in flat and interspersed order](images/palette-analogous-complementary.svg)

A palette is generated the first time a track is seen and cached in a `Ref` for the rest of that track, so the colours stay stable while a song plays.

### Assigning colours to lights

At startup the app asks the bridge which channels the entertainment zone has (`GET /clip/v2/resource/entertainment_configuration/{zone}`), so it drives exactly the lights in your zone, sorted by channel ID.

`getColor(index)` is a 0-based circular index: the first light gets the first colour, and the palette repeats after the last one. With a three-colour palette `[c1, c2, c3]` and seven lights:

| Light | 0 | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|---|
| Colour | c1 | c2 | c3 | c1 | c2 | c3 | c1 |

![Seven lights coloured from a three-colour analogous palette](images/light-mapping.svg)

## 4. The pulse

### Tempo

When a new track starts, the app asks Spotify for its tempo (`GET /v1/audio-features/{id}`) and pulses in time with it. Spotify restricted this endpoint for newer apps in late 2024; if it refuses, or the request fails, the app uses `default-bpm` (20 BPM, one pulse every 3 seconds) instead. The tempo is fetched once per track and cached.

`beats-per-pulse` slows fast tracks down: `2` pulses on every other beat, so a 128 BPM track pulses at 64 BPM.

### Shape

Each pulse has three phases, set by `pulse.attack`, `pulse.sustain` and `pulse.decay-curve`:

| Part of the pulse | Phase | Brightness curve |
|---|---|---|
| 0% to 20% | attack | rises linearly from 0 to 1 |
| 20% to 30% | sustain | holds at 1 |
| 30% to 100% | decay | falls as `(1 - x)²`, a soft fade that lingers |

![The pulse envelope over one beat: attack, sustain and decay](images/pulse-envelope.svg)

The curve value `p` becomes a brightness multiplier of `rest-level + peak-boost × p`, which is `0.15 + 2.5 × p` by default: a light glows at 15% of its colour's brightness at rest, and is boosted 2.65× at the peak.

### Keeping the colour at the peak

The brightness multiplier is applied in HSB: only the brightness is scaled (and capped at 100%), while the hue and saturation stay the same. The engine used to multiply each RGB channel instead, which capped the strongest channel at 255 while the others kept rising, so bright colours washed out towards yellow and white at every peak:

![One pulse of the base colour with RGB multiplication and with HSB brightness scaling](images/peak-brightness.svg)

A property test checks that at the peak the hue never moves by more than 2°.

### The wave

Each light starts its pulse `pulse.stagger` (0.15) of a beat after the one before, so the pulse travels across the zone instead of flashing all lights at once.

![Each light's brightness over two beats, offset by 0.15 of a beat per light](images/pulse-wave.svg)

Put together, this is what the seven lights show over two beats:

![The actual colour of each light over two beats](images/pulse-timeline.svg)

The pulse is computed by the pure functions `Pulse.level`, `Pulse.brightness` and `SyncEngine.pulseFrame`, so it's easy to test: the tests cover the envelope's shape, the stagger, the tempo, the brightness at rest and at the peak, and that every value stays between 0 and 255 for any tuning.

## 5. Pause

When playback stops, or Spotify can't be reached, the lights don't freeze on the last frame. They fade from whatever they were showing to `pause.resting-colour` over `pause.fade-out-ms`, then hold that colour until music starts again. Before anything has played, the zone shows the resting colour.

![Playback stops at 2 seconds, and the lights fade to the resting colour over 2 seconds](images/pause-fade.svg)

The fade is a small state machine (`Renderer`): it remembers the last frame that played and when the pause started, and blends each light towards the resting colour. Resuming playback clears the pause, so the next pause fades from the new frame. Because frames keep flowing during a pause, the bridge's stream never goes quiet.

## 6. Streaming to Hue

Hue's Entertainment API takes a stream of UDP packets secured with DTLS, instead of one REST call per light change.

1. `HueClient.getChannels` reads the zone's channel IDs from the bridge.
2. `HueClient.setStreaming` calls the bridge's CLIP v2 API (`PUT /clip/v2/resource/entertainment_configuration/{zone}`) with `{"action": "start"}`. This opens the streaming port.
3. `DtlsEmitter` opens a DTLS connection to the bridge on port 2100 using Californium Scandium. It authenticates with a pre-shared key: the identity is `HUE_USERNAME` and the key is `HUE_PSK`.
4. Each frame is encoded with scodec into this layout:

| Bytes | Field | Value |
|---|---|---|
| 9 | protocol name | `HueStream` |
| 2 | version | `02 00` |
| 1 | sequence | counts up with every frame, wrapping from 255 back to 0 |
| 2 | reserved | `00 00` |
| 1 | colour space | `00` (RGB) |
| 1 | reserved | `00` |
| 36 | entertainment configuration ID | the zone's UUID as text |
| 7 per light | channel and colour | channel ID (1 byte), then red, green and blue as 16-bit big-endian values |

A frame is 52 bytes plus 7 per channel, so 101 bytes for seven lights. The colour engine works in 8-bit colour, so each value is shifted left by 8 bits to fill the 16-bit fields.

5. When the app stops, the stream's finalizer calls `setStreaming(active = false)` and the DTLS connector is closed.

## 7. Configuration

All light-show settings live under `light-show` in `src/main/resources/reference.conf`, with a description of each. Override any of them in `application.conf`:

```hocon
light-show {
  palette = "triadic"
  beats-per-pulse = 2
  pulse.peak-boost = 1.5
  pause.resting-colour = "#0a0a20"
}
```

| Setting | Default | What it does |
|---|---|---|
| `palette` | `analogous` | Palette type (see [Palettes](#palettes)) |
| `default-bpm` | `20` | Tempo used when Spotify can't provide one |
| `beats-per-pulse` | `1` | Pulse once every N beats |
| `sync-offset-ms` | `-150` | Shifts the lights relative to the audio |
| `frame-interval-ms` | `16` | Time between frames |
| `pulse.stagger` | `0.15` | Delay between neighbouring lights, in beats |
| `pulse.attack` | `0.2` | Fraction of a pulse spent rising |
| `pulse.sustain` | `0.1` | Fraction of a pulse held at full brightness |
| `pulse.decay-curve` | `2.0` | Shape of the fade; higher fades faster |
| `pulse.rest-level` | `0.15` | Brightness multiplier at rest |
| `pulse.peak-boost` | `2.5` | Brightness added at the peak |
| `pause.resting-colour` | `#1e1408` | Colour shown while nothing plays |
| `pause.fade-out-ms` | `2000` | How long the fade to the resting colour takes |

The config is validated when the app starts: an unknown palette name, an invalid colour, or tuning that can't work (for example `attack + sustain` of 1 or more) stops the app with a message saying what's wrong.

## Known limitations

The [roadmap](roadmap.md) tracks fixes for these.

- **No token refresh.** The access token is never refreshed, and the configured refresh token isn't used yet. When the token expires (Spotify tokens last about an hour), polls come back unauthorised and are treated as "nothing playing", so the lights fade to rest until the app is restarted.
- **Pulses aren't aligned to the downbeat.** The tempo sets how fast the lights pulse, but pulses are counted from the start of the track, so they can be offset from the music's actual beats. `sync-offset-ms` shifts them by hand.
- **Certificate checks are off.** The Hue bridge serves a self-signed certificate, so the app's HTTP client is built without certificate verification. That client is shared, so Spotify calls skip verification too. A separate client for the bridge would keep verification on for Spotify.

## Regenerating the images

The images on this page are drawn from the app's own code (`Palette.random`, `RGB`, `Pulse`, `Renderer` and `SyncEngine.pulseFrame`), so they stay accurate when the engine changes. The comparison in [Keeping the colour at the peak](#keeping-the-colour-at-the-peak) also redraws the old RGB multiplication, for reference. To redraw them:

```sh
sbt "Test/runMain DocImages"
```
