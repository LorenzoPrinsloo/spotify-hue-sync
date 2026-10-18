# spotify-hue-sync

Syncs what's playing on Spotify to Philips Hue lights in real time.

It polls Spotify for the current track, pulses on the beat (detected live from your audio, or from Spotify's tempo) using a colour palette built from colour theory (analogous, complementary, triadic, tetradic or monochromatic), and streams light frames over DTLS to the Hue entertainment area you choose. With live audio, the lights also follow the music's energy, flash on big hits and sparkle with the hi-hats, and split into rhythm lights that play the kick pattern on the beat and colour lights that swell every other beat and move through the palette as the music goes higher or lower. When the music stops, the lights fade to a resting colour. While it runs, a settings page in the browser changes any of this, including which area plays, from the next song.

Built with Scala 3, Cats Effect, fs2 and http4s.

## Documentation

- [Architecture](docs/architecture.md): components, packages and main types, with diagrams
- [How it works](docs/how-it-works.md): Spotify sign-in, the polling and rendering loops, the colour engine and colour theory, the pulse animation, live beat detection, light roles and reacting to the music, the Hue streaming protocol and choosing the area, configuration and the settings page
- [Roadmap](docs/roadmap.md): what's still to do

## Configuration

Credentials are read from environment variables. Copy `.env.example` to `.env`, fill it in, and never commit it.

| Variable | What it is |
|---|---|
| `SPOTIFY_CLIENT_ID` | Client ID of your Spotify developer app |
| `SPOTIFY_CLIENT_SECRET` | Client secret of your Spotify developer app |
| `SPOTIFY_REFRESH_TOKEN` | Optional. The app starts from this refresh token without a browser sign-in when no refresh token has been saved yet, and signs in through the browser when it is empty or Spotify rejects it |
| `SPOTIFY_TOKEN_FILE` | Optional. Where the app saves the refresh token; overrides `spotify.token-file`. Empty counts as unset. See [The saved refresh token](#the-saved-refresh-token) |
| `HUE_BRIDGE_ADDRESS` | IP address of your Hue bridge |
| `HUE_USERNAME` | Hue application key |
| `HUE_PSK` | Hue entertainment client key, 32 hex digits |
| `HUE_ZONE_ID` | Optional. ID of the entertainment area to play on; `light-show.area` picks one by name instead, and with only one music area on the bridge neither is needed |

To pulse on the actual beat, route your audio through [BlackHole](https://github.com/ExistentialAudio/BlackHole) and set `light-show.beat-source = "audio"`; the app then detects the beat live (see [Live beat detection](docs/how-it-works.md#live-beat-detection) for setup).

The light show (palette type, beat source, pulse shape, resting colour and more) is configured under `light-show`. The defaults and a description of each setting are in `src/main/resources/reference.conf`; override them in `application.conf`. See [How it works: Configuration](docs/how-it-works.md#7-configuration).

## The saved refresh token

After a sign-in, and whenever Spotify sends a new refresh token, the app saves the newest one to a file, so the next start needs no browser and no `SPOTIFY_REFRESH_TOKEN`.

- By default the file is `$XDG_CONFIG_HOME/spotify-hue-sync/refresh-token`, or `~/.config/spotify-hue-sync/refresh-token` when `XDG_CONFIG_HOME` is unset.
- To move it, set `spotify.token-file` in `application.conf` or the `SPOTIFY_TOKEN_FILE` variable. The variable wins when both are set.
- The saved token beats `SPOTIFY_REFRESH_TOKEN`. If Spotify rejects it, the app deletes the file and falls back to `SPOTIFY_REFRESH_TOKEN`, then to the browser sign-in.
- To switch token or account, delete the file: the app then uses `SPOTIFY_REFRESH_TOKEN` if it is set, and otherwise signs in through the browser.
- The file is owner-only (mode 0600, in a directory of mode 0700 if the app created it). The app tightens it if it finds it readable by others.

## Running

```sh
set -a; source .env; set +a
sbt run
```

While it runs, the settings page at [http://127.0.0.1:7071](http://127.0.0.1:7071) changes any light-show setting for the next song, without touching the config file, and can copy what you have as config. See [The settings page](docs/how-it-works.md#8-the-settings-page).

## Testing and linting

```sh
sbt check   # lint, then test with coverage measured: what CI runs on every push
sbt test    # tests only
sbt fix     # apply Scalafix's rewrites and format the code
```

`sbt check` also measures test coverage with [scoverage](https://github.com/scoverage/sbt-scoverage) and fails if it drops below 90% of statements or 85% of branches overall, or below 70% of statements in any one file, so new code can't go untested behind the totals. The report is in `target/scala-3.9.0/scoverage-report/index.html`. The minimums are in `build.sbt`, along with the few classes left out of the measurement and why: `Main`, which only wires the app together, and the code that needs real hardware or a real network to run (the audio device, the DTLS socket to the bridge, and the stream joining the two). The settings page is tested in Node.js but not measured, since Scala 3 records coverage on the JVM only.

[Scalafix](https://scalacenter.github.io/scalafix/) checks the code against the rules in `.scalafix.conf` (tests use `.scalafix.test.conf`): Scalafix's own rules, [xuwei-k's scalafix-rules](https://github.com/xuwei-k/scalafix-rules), [Typelevel's](https://github.com/typelevel/typelevel-scalafix) and the repo's own, in `scalafix-rules/`. They catch Scala 2 leftovers (`implicit`, `cats.implicits`), unsafe calls (`Option.get`, `isInstanceOf`, running IO outside the app's entry point), simplifications such as `Option.when`, a match on only `Some` and `None` (or `Left` and `Right`) that reads better as `fold`, and a sealed trait of plain case classes and objects that reads better as an `enum`. `sbt lint` fails on any error; most come with a rewrite that `sbt fix` applies.

[scalafmt](https://scalameta.org/scalafmt/) formats the code and the sbt files with the settings in `.scalafmt.conf`. It keeps the existing line breaks and braces where it can and doesn't align, so a change only reformats the lines it touches. `sbt lint` fails if anything isn't formatted; `sbt fix` formats it.

The settings page's tests run in Node.js, which needs to be installed. Property-based tests (munit + ScalaCheck) cover the colour theory, every palette type, the pulse envelope, the light roles, the beat tracker, kick pattern and pitch (against generated audio), the pause fade, config loading and validation, the settings page's API and sliders, and the Hue packet format. The Spotify and Hue clients are tested against fake HTTP servers, and the whole engine runs against a fake Spotify on Cats Effect's simulated clock.
