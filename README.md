# spotify-hue-sync

Syncs what's playing on Spotify to Philips Hue lights in real time.

It polls Spotify for the current track, pulses on the beat (detected live from your audio, or from Spotify's tempo) using a colour palette built from colour theory (analogous, complementary, triadic, tetradic or monochromatic), and streams light frames to a Hue entertainment zone over DTLS. With live audio, the lights also follow the music's energy, flash on big hits and sparkle with the hi-hats, and split into rhythm lights that play the kick pattern on the beat and colour lights that swell every other beat and move through the palette as the music goes higher or lower. When the music stops, the lights fade to a resting colour.

Built with Scala 3, Cats Effect, fs2 and http4s.

## Documentation

- [Architecture](docs/architecture.md): components, packages and main types, with diagrams
- [How it works](docs/how-it-works.md): Spotify sign-in, the polling and rendering loops, the colour engine and colour theory, the pulse animation, live beat detection and reacting to the music, and the Hue streaming protocol
- [Roadmap](docs/roadmap.md): what's still to do

## Configuration

Credentials are read from environment variables. Copy `.env.example` to `.env`, fill it in, and never commit it.

| Variable | What it is |
|---|---|
| `SPOTIFY_CLIENT_ID` | Client ID of your Spotify developer app |
| `SPOTIFY_CLIENT_SECRET` | Client secret of your Spotify developer app |
| `SPOTIFY_REFRESH_TOKEN` | Refresh token for your Spotify account |
| `SPOTIFY_SP_DC` | Optional. Spotify web session cookie, only used for the first authorisation |
| `HUE_BRIDGE_ADDRESS` | IP address of your Hue bridge |
| `HUE_USERNAME` | Hue application key |
| `HUE_PSK` | Hue entertainment client key |
| `HUE_ZONE_ID` | Optional. ID of the entertainment area to play on; `light-show.area` picks one by name instead, and with only one music area on the bridge neither is needed |

To pulse on the actual beat, route your audio through [BlackHole](https://github.com/ExistentialAudio/BlackHole) and set `light-show.beat-source = "audio"`; the app then detects the beat live (see [Live beat detection](docs/how-it-works.md#live-beat-detection) for setup).

The light show (palette type, beat source, pulse shape, resting colour and more) is configured under `light-show`. The defaults and a description of each setting are in `src/main/resources/reference.conf`; override them in `application.conf`. See [How it works: Configuration](docs/how-it-works.md#7-configuration).

## Running

```sh
set -a; source .env; set +a
sbt run
```

While it runs, the settings page at [http://127.0.0.1:7071](http://127.0.0.1:7071) changes any light-show setting for the next song, without touching the config file, and can copy what you have as config. See [The settings page](docs/how-it-works.md#8-the-settings-page).

## Testing and linting

```sh
sbt check   # lint, then test: what CI runs on every push
sbt test    # tests only
sbt fix     # apply Scalafix's rewrites
```

[Scalafix](https://scalacenter.github.io/scalafix/) checks the code against the rules in `.scalafix.conf` (tests use `.scalafix.test.conf`): Scalafix's own rules, [xuwei-k's scalafix-rules](https://github.com/xuwei-k/scalafix-rules) and [Typelevel's](https://github.com/typelevel/typelevel-scalafix). They catch Scala 2 leftovers (`implicit`, `cats.implicits`), unsafe calls (`Option.get`, `isInstanceOf`, running IO outside the app's entry point) and simplifications such as `Option.when`. `sbt lint` fails on any error; most come with a rewrite that `sbt fix` applies.

The settings page's tests run in Node.js, which needs to be installed. Property-based tests (munit + ScalaCheck) cover the colour theory, every palette type, the pulse envelope, the light roles, the beat tracker, kick pattern and pitch (against generated audio), the pause fade, config loading and validation, the settings page's API and sliders, and the Hue packet format. The Spotify and Hue clients are tested against fake HTTP servers, and the whole engine runs against a fake Spotify on Cats Effect's simulated clock.
