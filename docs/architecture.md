# Architecture

spotify-hue-sync is a single Cats Effect application. It polls Spotify for what is playing, turns the current track into a colour palette, and streams a light frame to a Philips Hue entertainment zone about 60 times a second.

## Components

```mermaid
flowchart LR
    subgraph Spotify
        API[Web API<br/>/me/player]
        ACC[Accounts<br/>OAuth]
    end

    subgraph App[spotify-hue-sync]
        CB[Callback server<br/>127.0.0.1:7070]
        SC[SpotifyClient]
        SE[SyncEngine]
        PAL[Palette / RGB<br/>colour engine]
        HC[HueClient]
        DE[DtlsEmitter]
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
    SE -- palette per track --> PAL
    SE -- frames --> DE
    HC -- start / stop streaming --> CLIP
    DE -- DTLS PSK packets --> ENT
```

## Packages

| Package | What lives there |
|---|---|
| (root) | `Main`, which wires everything together, and `SyncEngine`, which turns playback state into light frames |
| `client` | Everything that talks to the outside world: `SpotifyClient`, `CachedSpotifyClient`, `HueClient` and `DtlsEmitter` |
| `domain.config` | `AppConfig` and `HueConfig`, loaded from `application.conf` with pureconfig |
| `domain.engine` | The colour engine: `RGB`, `Palette`, `PaletteType`, `Order` and `LocalPlayback` |
| `domain.hue` | The Hue streaming protocol: `Frame`, `LightUpdate` and their binary codecs |
| `domain.spotify` | Spotify API models: `PlaybackState`, `SpotifyToken`, `SpotifySegment`, `SpotifyCredentials` |
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
    }
    class HueConfig {
        zoneId: String
        creds: HueCredentials
    }
    class HueCredentials {
        address: String
        username: String
        psk: String
    }
    class SpotifyCredentials {
        clientId: String
        clientSecret: String
        refreshToken: String
    }

    class SyncEngine~F~ {
        -paletteCache: Ref~Map~
        +run: Stream~Frame~
        +make(config, spotify)$ F~SyncEngine~
        +pulseFrame(zoneId, bpm, progressMs, palette)$ Frame
    }

    class SpotifyClient~F~ {
        -tokenRef: Ref~Option~
        -authCodeDeferred: Deferred~String~
        +initialAuth: F~String~
        +getPlaybackState: F~Option~
        +getTrackAnalysis(id) F~Vector~
    }
    class CachedSpotifyClient~F~ {
        -cache: Ref~Map~
        +getTrackAnalysis(id) F~Vector~
    }

    class HueClient~F~ {
        +setStreaming(zoneId, active) F~Unit~
    }
    class DtlsEmitter~F~ {
        +resource: Resource~DTLSConnector~
        +send(connector, frame) F~Unit~
    }

    class PlaybackState {
        trackId: TrackId
        progress: FiniteDuration
        isPlaying: Boolean
        albumCoverUrl: String
    }
    class LocalPlayback {
        state: PlaybackState
        receivedAt: Instant
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
        +compliment: Option~RGB~
        +analogous: List~RGB~
    }
    class PaletteType {
        <<enumeration>>
        Complementary
        Monochromatic
        Analogous
        Triadic
        Tetradic
        AnalogousComplementary(order)
    }
    class Order {
        <<enumeration>>
        Flat
        Interspersed
    }

    class Frame {
        zoneId: String
        updates: List~LightUpdate~
    }
    class LightUpdate {
        id: Int
        color: RGB
    }

    Main --> AppConfig
    Main --> SyncEngine
    Main --> CachedSpotifyClient
    Main --> HueClient
    Main --> DtlsEmitter
    AppConfig *-- HueConfig
    AppConfig *-- SpotifyCredentials
    HueConfig *-- HueCredentials
    CachedSpotifyClient --|> SpotifyClient
    SyncEngine --> SpotifyClient
    SyncEngine ..> LocalPlayback
    SyncEngine ..> Palette
    SyncEngine ..> Frame
    LocalPlayback *-- PlaybackState
    Palette *-- RGB
    Palette ..> PaletteType
    PaletteType ..> Order
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
    M->>E: SyncEngine.make (creates the palette cache)
    M->>H: setStreaming(zone, active = true)
    M->>D: open DTLS connection to bridge:2100
    loop until stopped
        E->>D: Frame
        D->>D: encode and send packet
    end
    M->>H: setStreaming(zone, active = false)
    M->>D: stop DTLS connector
```

## Effects and concurrency

- Everything runs in `IO` through tagless-final `F[_]` constraints (`Async`, `Network`).
- Shared state lives in Cats Effect primitives:
  - `Ref` for the Spotify access token, the palette cache and the track-analysis cache
  - `Deferred` to hand the OAuth code from the callback server to the client
  - `SignallingRef` for the latest playback state shared by the poller and the renderer
- The poller and the renderer are two fs2 streams merged into one, so stopping the app cancels both.
