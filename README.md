# spotify-hue-sync

Syncs what's playing on Spotify to Philips Hue lights in real time.

It polls Spotify for the current track, builds a colour palette for it using colour theory (analogous and complementary schemes), and streams light frames to a Hue entertainment zone over DTLS.

Built with Scala 3, Cats Effect, fs2 and http4s.

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
| `HUE_ZONE_ID` | ID of the entertainment zone to sync |

## Running

```sh
set -a; source .env; set +a
sbt run
```

## Testing

```sh
sbt test
```

Property-based tests (munit + ScalaCheck) cover the colour maths and palette generation.
