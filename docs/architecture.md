# Architecture

spotify-hue-sync is a single Cats Effect application. It polls Spotify for what is playing, turns the current track into a tempo and a colour palette, and streams a light frame to a Philips Hue entertainment zone about 60 times a second. A settings page, served by the app, changes the light show's settings for the next song.

The build has three modules:

| Module | Directory | What it is |
|---|---|---|
| `root` | `.` | The app |
| `model` | `model/` | The settings, their rules and JSON codecs, the colours (`RGB`, with plain-Scala HSB conversion) and the palette and light-role names; built for the JVM and for Scala.js |
| `ui` | `ui/` | The settings page, in Laminar, compiled to JavaScript and served by the app from `ui/main.js` beside `src/main/resources/ui/index.html` |

## Components

```mermaid
flowchart LR
    subgraph Spotify
        API[Web API<br/>/me/player<br/>/audio-features]
        ACC[Accounts<br/>OAuth]
    end

    subgraph App[spotify-hue-sync]
        CB[Callback server<br/>127.0.0.1:7070]
        SC[SpotifyClient]
        SE[SyncEngine]
        PAL[Palette / RGB / Pulse<br/>colour engine]
        REN[Renderer<br/>pause fade]
        BT[BeatTracker<br/>live beat]
        HC[HueClient]
        DE[DtlsEmitter]
        LS[LiveSettings]
        UI[Settings page<br/>127.0.0.1:7071]
    end

    subgraph Hue[Hue bridge]
        CLIP[CLIP v2 REST API]
        ENT[Entertainment stream<br/>UDP 2100]
    end

    ACC -- redirect with code --> CB
    CB -- auth code --> SC
    SC -- token exchange --> ACC
    SC -- poll playback state --> API
    SE -- getPlaybackState --> SC
    SE -- palette and pulse --> PAL
    SE -- when paused --> REN
    BH[BlackHole<br/>audio input] --> BT
    BT -- beat, energy, hits --> SE
    SE -- frames --> ES[EntertainmentStream]
    ES -- start / stop streaming per area --> HC
    ES -- frames --> DE
    UI -- change, reset, apply now --> LS
    LS -- settings, as each song starts --> SE
    HC -- read channels, start / stop streaming --> CLIP
    DE -- DTLS PSK packets --> ENT
```

## Packages

| Package | What lives there |
|---|---|
| (root) | `Main`, which wires everything together, and `SyncEngine`, which turns playback state into light frames |
| (root) | Also `Lights`: the bridge's entertainment areas and the channels in each, as the engine sees them |
| `client` | Everything that talks to the outside world: `SpotifyClient`, `CachedSpotifyClient`, `HueClient` (including the list of entertainment areas), `DtlsEmitter`, and `EntertainmentStream`, which streams each frame to its area and moves the stream when the area changes |
| `audio` | `AudioInput`: captures audio from an input device such as BlackHole |
| `beat` | Live beat detection: `Fft`, `OnsetDetector`, `BeatTracker`, `BeatState`, `Groove` (the bar's kick pattern), `AudioReading` and the `BeatTracking` stream |
| `domain.config` | `AppConfig`, `HueConfig`, `UiConfig` and `LightShowConfig` (with `PulseSettings`, `PauseSettings`, `AudioSettings`, `ReactiveSettings` and `SwellSettings`, in `model`), loaded with pureconfig from `application.conf` on top of the defaults in `reference.conf` by `LightShowConfigReaders`; `SettingsView`, `SettingsProblems` and `ConfigSnippet` for the settings page |
| `settings` | `LiveSettings` (the settings for this session, taken up as each song starts), `SettingsRoutes` (the page and its API), `ShowControls` (what the page asks of the running show: apply now, the areas, the song's palette) and `SettingsServer` |
| `ui` | The settings page: `SettingsPage`, `Sections` (every setting on it), `Api` |
| `domain.engine` | The colour engine and animation: `RGB`, `Palette`, `PaletteType`, `Order`, `Pulse` (the envelopes), `Effects` (the reactions to the music), `LightRole` (each light's job), `Renderer` (the pause fade) and `LocalPlayback` |
| `domain.hue` | The Hue streaming protocol: `Zone`, `Frame`, `LightUpdate` and their binary codecs; `MusicArea` (in `model`), an entertainment area and how the one to play on is chosen |
| `domain.spotify` | Spotify API models: `PlaybackState`, `SpotifyToken`, `SpotifySession`, `SpotifySegment`, `SpotifyCredentials` |
| `givens.circe` | Shared circe codecs |

## Main types

```mermaid
classDiagram
    direction LR

    class Main {
        +run(args) IO~ExitCode~
    }

    class AppConfig {
        spotify: SpotifyCredentials
        hue: HueConfig
        pollingIntervalMs: Int
        lightShow: LightShowConfig
        ui: UiConfig
    }
    class LightShowConfig {
        area: Option~String~
        palette: PaletteType
        beatSource: BeatSource
        audio: AudioSettings
        reactive: ReactiveSettings
        defaultBpm: Double
        beatsPerPulse: Double
        syncOffsetMs: Long
        frameIntervalMs: Long
        pulse: PulseSettings
        pause: PauseSettings
    }
    class PulseSettings {
        stagger: Double
        attack: Double
        sustain: Double
        decayCurve: Double
        restLevel: Double
        peakBoost: Double
    }
    class PauseSettings {
        restingColour: RGB
        fadeOutMs: Long
    }

    class SyncEngine~F~ {
        -current: Ref~Option~
        +run: Stream~Frame~
        +showFor(trackId) F~TrackShow~
        +applyNow: F~Unit~
        +palette: F~Option~
        +make(config, live, spotify, zone, lights, random, audio)$ F~SyncEngine~
        +pulseFrame(zone, palette, pulses, settings, effects)$ Frame
    }
    class TrackShow {
        tempo: Option~Double~
        palette: Palette
        settings: LightShowConfig
        zone: Zone
        +bpm: Double
    }
    class LiveSettings~F~ {
        fromFile: LightShowConfig
        +view: F~SettingsView~
        +update(next) F~Either~
        +reset: F~SettingsView~
        +startSong: F~LightShowConfig~
    }
    class Lights~F~ {
        <<trait>>
        +areas: F~List~MusicArea~~
        +zone(area) F~Option~Zone~~
    }
    class EntertainmentStream {
        +apply(session, send)(frames)$ Stream~Nothing~
        +toBridge(hue, emitter)(frames)$ Stream~Nothing~
    }

    class SpotifyClient~F~ {
        +getPlaybackState: F~Option~
        +getTempo(id) F~Option~
    }
    class HueClient~F~ {
        +areas: F~List~MusicArea~~
        +getChannels(zoneId) F~List~
        +setStreaming(zoneId, active) F~Unit~
    }
    class DtlsEmitter~F~ {
        +send(connector, frame) F~Unit~
    }

    class Pulse {
        +level(phase, settings)$ Double
        +onBeat(phase, settings)$ Double
        +rhythm(groove, beats, settings)$ Double
        +brightness(level, settings)$ Double
    }
    class Effects {
        intensity: Double
        hueShift: Int
        flash: Double
        sparkle: Option~Sparkle~
        roles: Vector~LightRole~
        rhythm: Option~Rhythm~
        paletteShift: Double
        swell: SwellSettings
        +at(now, reading, lights, settings)$ Effects
    }
    class Renderer {
        +playing(frame)$ RenderState and Frame
        +paused(state, now, zone, pause)$ RenderState and Frame
        +fade(from, to, progress)$ Frame
    }
    class RenderState {
        lastPlayed: Option~Frame~
        pausedSince: Option~FiniteDuration~
    }

    class Palette {
        colors: Vector~RGB~
        +random(mode)$ Option~Palette~
        +getColor(index) RGB
    }
    class RGB {
        r: ByteInt
        g: ByteInt
        b: ByteInt
        +rotateHue(degrees) RGB
        +scaleBrightness(factor) RGB
        +mix(other, t) RGB
    }
    class PaletteType {
        <<enumeration>>
        Analogous
        Complementary
        Monochromatic
        Triadic
        Tetradic
        AnalogousComplementary(order)
    }

    class Zone {
        id: String
        channels: List~Int~
    }
    class Frame {
        zoneId: String
        updates: List~LightUpdate~
        sequence: Int
    }
    class LightUpdate {
        id: Int
        color: RGB
    }

    Main --> AppConfig
    Main --> SyncEngine
    Main --> HueClient
    Main --> DtlsEmitter
    Main --> EntertainmentStream
    Main --> LiveSettings
    SyncEngine --> LiveSettings
    SyncEngine --> Lights
    TrackShow *-- LightShowConfig
    TrackShow *-- Zone
    EntertainmentStream --> HueClient
    EntertainmentStream --> DtlsEmitter
    AppConfig *-- LightShowConfig
    LightShowConfig *-- PulseSettings
    LightShowConfig *-- PauseSettings
    LightShowConfig --> PaletteType
    SyncEngine --> SpotifyClient
    SyncEngine --> Zone
    SyncEngine ..> TrackShow
    SyncEngine ..> Pulse
    SyncEngine ..> Effects
    SyncEngine ..> Renderer
    Renderer ..> RenderState
    TrackShow *-- Palette
    Palette *-- RGB
    Palette ..> PaletteType
    Frame *-- LightUpdate
    LightUpdate *-- RGB
    DtlsEmitter ..> Frame
```

## Startup and shutdown

`Main` builds everything as a single `Resource`, so every connection and server is closed in reverse order when the app stops.

```mermaid
sequenceDiagram
    participant M as Main
    participant C as Config
    participant S as SpotifyClient
    participant H as HueClient
    participant D as DtlsEmitter
    participant E as SyncEngine

    M->>C: load application.conf (env variables)
    M->>S: build HTTP client and start callback server on :7070
    M->>H: list the entertainment areas
    H-->>M: areas
    M->>M: choose the area (light-show.area, hue.zone-id or the only music area)
    M->>H: getChannels(area)
    H-->>M: channel ids
    M->>E: SyncEngine.make (live settings, area's zone)
    M->>M: start the settings page on :7071
    loop until stopped
        E->>M: Frame (for an area)
        alt first frame for this area
            M->>D: close the previous area's DTLS connection, if any
            M->>H: setStreaming(previous area, active = false)
            M->>H: setStreaming(area, active = true)
            M->>D: open a new DTLS connection to bridge:2100
        end
        D->>D: encode and send packet
    end
    M->>D: close the DTLS connection
    M->>H: setStreaming(area, active = false)
```

## Effects and concurrency

- Everything runs in `IO` through tagless-final `F[_]` constraints (`Async`, `Network`).
- Shared state lives in Cats Effect primitives:
  - `Ref` for the Spotify session (access and refresh tokens), the current song's show, the pending and applied settings (`LiveSettings`), each area's channels once read (`Lights`), and the track-analysis cache
  - `Deferred` to hand the OAuth code from the callback server to the client
  - `SignallingRef` for the latest playback state shared by the poller and the renderer
- The poller and the renderer are two fs2 streams merged into one, so stopping the app cancels both.
- `Hotswap` holds the entertainment session on the current area, so moving to another area closes the old session before opening the new one, and stopping the app closes the last.
- The settings page's server is a `Resource` like the rest, so it stops with the app.
- The renderer threads its pause state through `evalMapAccumulate`, and reads time from `Clock`, so the whole engine runs on Cats Effect's simulated clock in tests (`TestControl`).
