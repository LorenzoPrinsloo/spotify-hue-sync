# ADR 0004: Package the app as a multi-architecture Docker image, with a configurable sign-in callback host

Issue: LorenzoPrinsloo/spotify-hue-sync#17 · Status: proposed

## Context

Running the app needs sbt and a JDK. Three more things stop it working in a container. The sign-in callback is hard-wired to 127.0.0.1:7070 (`SpotifyClient.serverResource`). The settings page defaults to 127.0.0.1. And the bundled config asks for `beat-source = "audio"` when a container has no audio device. At startup the app also doesn't say that it is running on Spotify timing.

## Decision

Add a new config key, `callback.host`, read into `CallbackConfig(host: Host)` and validated by its pureconfig reader. reference.conf sets its default to 127.0.0.1. The port stays 7070 and the redirect URI doesn't change. `SpotifyClient.resource` takes the config in a second parameter list and passes the host to `serverResource`. Right after loading the config, `Main` logs one line if the beat source is Spotify.

The `Dockerfile` has two stages, and nothing in it is specific to one architecture:
- Build: `FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build` with the sbt launcher tarball. The class files, jars and main.js don't depend on the architecture, so buildx compiles once, natively, rather than under QEMU. The stage first runs `sbt update` on the build files alone, so that layer can be cached. Then `sbt -batch -error 'export root/Runtime/fullClasspath' | tail -n1` captures the classpath without sbt's banner, and it also links the Scala.js page through the existing resourceGenerator. Each entry, in order, is copied to `/app/lib/NNN-<basename>` (numbered, so equal base names can't collide). The stage then writes `/app/bin/run`, which runs `java -Dconfig.file=/app/docker.conf -cp <those paths, in order> Main`.
- Runtime: `eclipse-temurin:21-jre`, one per target platform. It runs as a non-root user that owns `/data`, with `ENV SPOTIFY_TOKEN_FILE=/data/refresh-token` and `VOLUME /data`. No sbt plugins are added.

`docker/application.conf` loads in this order: `include classpath("application.conf")`, then the optional `include file("/config/application.conf")`, then `callback.host = "0.0.0.0"`, `ui.host = "0.0.0.0"` and `light-show.beat-source = "spotify"`. Because the three container keys come last, they win even when a user mounts a full copy of the bundled file.

## Components and call direction

- `domain.config.CallbackConfig` (new): the host, its `ConfigReader[Host]`, and the `Port` constant 7070.
- `domain.config.AppConfig`: gains `callback: CallbackConfig = CallbackConfig()`.
- `client.SpotifyClient` companion: `serverResource(codes, host, port = CallbackConfig.Port)` and `resource(client, creds, store)(callback)`.
- `Main`: adds `spotifyTiming(config)` and passes `config.callback` on.
- `reference.conf`: adds `callback { host = "127.0.0.1" }`, with a comment.
- New files: `Dockerfile`, `docker/application.conf`, and `.dockerignore` (`**/target`, `.git`, `.bsp`, `.metals`, `.bloop`, `.idea`, `.vscode`, `**/node_modules`, `.env`, `.env.*`).
- Docs: a Docker section in the README; `docs/how-it-works.md` and `docs/architecture.md` change where they describe the callback server.

Calls go in one direction: Main → AppConfig.load → SpotifyClient.resource(…)(config.callback) → serverResource(codes, host) → EmberServerBuilder.

## Contracts

- Config key `callback.host` (an IP or host name, default "127.0.0.1"): new and additive. The default keeps today's behaviour.
- `AppConfig.callback`: a new field with a default, so it's additive.
- `SpotifyClient.serverResource` and `SpotifyClient.resource` gain parameters. That's a source change, but their only callers are `resource` and `Main`.
- Unchanged: the redirect URI `http://127.0.0.1:7070/callback`, `/callback`, `SpotifyCredentials`, the settings JSON, the shared `model` types, and the `ui.host` key.
- Environment variables: none are added. The image presets the existing `SPOTIFY_TOKEN_FILE`, and `SPOTIFY_REFRESH_TOKEN` still works.
- The image itself (new): it exposes 7070 and 7071, keeps the token on the `/data` volume, and reads an optional `/config/application.conf`.

## Failure modes

- Config: an invalid `callback.host` is fatal at startup. pureconfig reports it as a `CannotConvert` inside the existing `ConfigReaderException`. `Main` already logs `prettyPrint()`, which names the key, and exits with `ExitCode.Error` before any network call. pureconfig's existing failure ADT carries this, so no new sealed type is needed.
- Callback bind: a port that's taken or an address that isn't local raises a `BindException` from the resource. That's fatal, as it is today.
- Docker build: a failed sbt step or an empty classpath fails the build.
- Container with no token and 7070 not published: sign-in waits on the logged URL, as it does today. The README covers this.

## Config

`callback.host`, default "127.0.0.1", set in reference.conf with a comment. `ConfigReader[Host]` checks it at startup through `Host.fromString`. Anything else fails with `CannotConvert(value, "Host", "expected an IP address or host name, such as 127.0.0.1 or 0.0.0.0")`, and the app refuses to start. The image sets it to 0.0.0.0 in `docker/application.conf`. The bundled `application.conf` is unchanged.

## Observability

- New INFO line from `Main`, logged once right after the config loads when the beat source is spotify. It has the field `beat_source=spotify` and says: "Live beat detection is off because the beat source is Spotify; pulses follow Spotify's playback position and tempo". It isn't logged for audio.
- An invalid host appears in the existing ERROR `prettyPrint` line, with the key and the value.
- The "Settings page at <baseUri>" line is unchanged, so in the image it reads `http://0.0.0.0:7071`. The README therefore tells users to open `http://127.0.0.1:<published port>`.

## Program design

`src/main/scala/domain/config/CallbackConfig.scala` (new):

```scala
package domain.config

import com.comcast.ip4s.{Host, Port, ipv4, port}
import pureconfig.ConfigReader

/**
 * Where the Spotify sign-in callback server listens: `127.0.0.1` keeps it to this computer, `0.0.0.0` accepts it on every
 * interface (as in the Docker image). The port is always 7070, the one the registered redirect URI names.
 */
case class CallbackConfig(host: Host = CallbackConfig.DefaultHost)

object CallbackConfig {

  val DefaultHost: Host = ipv4"127.0.0.1"

  val Port: Port = port"7070"

  given ConfigReader[Host] = ???

  given ConfigReader[CallbackConfig] = ???
}
```

Changes to existing files:

```diff
diff --git a/src/main/scala/Main.scala b/src/main/scala/Main.scala
index 368ddcf..4985906 100644
--- a/src/main/scala/Main.scala
+++ b/src/main/scala/Main.scala
@@ -22,16 +22,20 @@ object Main extends IOApp {
       def palette: IO[Vector[RGB]] = engine.palette.map(_.fold(Vector.empty)(_.colors))
     }
 
+  /** Logged once at startup when the beat source is Spotify. */
+  private def spotifyTiming(config: AppConfig): IO[Unit] = ???
+
   override def run(args: List[String]): IO[ExitCode] = start(ConfigSource.default)
 
   def start(source: ConfigSource): IO[ExitCode] = {
     val resources = for {
       config <- Resource.eval(AppConfig.load[IO](source))
+      _ <- Resource.eval(spotifyTiming(config))
       clients <- HttpClients.resource[IO]
 
       env <- Resource.eval(Env[IO].entries.map(_.toMap))
       store <- Resource.eval(TokenStore.at[IO](TokenFile.locate(config.spotify.tokenFile, env)))
-      spotify <- SpotifyClient.resource[IO](clients.spotify, config.spotify, store)
+      spotify <- SpotifyClient.resource[IO](clients.spotify, config.spotify, store)(config.callback)
       hueClient = new HueClient[IO](config.hue.creds, clients.bridge)
       emitter = new DtlsEmitter[IO](config.hue.creds)
 
diff --git a/src/main/scala/client/SpotifyClient.scala b/src/main/scala/client/SpotifyClient.scala
index 3108333..9ac3f91 100644
--- a/src/main/scala/client/SpotifyClient.scala
+++ b/src/main/scala/client/SpotifyClient.scala
@@ -3,6 +3,7 @@ package client
 import cats.effect.*
 import cats.effect.implicits.genSpawnOps
 import cats.syntax.all.*
+import domain.config.CallbackConfig
 import domain.spotify.*
 import domain.spotify.types.TrackId
 import org.http4s.{client, *}
@@ -13,7 +14,7 @@ import org.http4s.client.dsl.Http4sClientDsl
 import org.http4s.ember.server.EmberServerBuilder
 import org.http4s.implicits.*
 import org.typelevel.log4cats.slf4j.Slf4jLogger
-import com.comcast.ip4s.{ipv4, port}
+import com.comcast.ip4s.{Host, Port}
 
 import scala.concurrent.duration.*
 import org.http4s.dsl.io.*
@@ -218,14 +219,13 @@ object SpotifyClient:
     }
   }
 
-  def serverResource[F[_]: Async: Network](codes: SignInCodes[F]): Resource[F, org.http4s.server.Server] =
-    EmberServerBuilder.default[F].withHost(ipv4"127.0.0.1").withPort(port"7070").withHttpApp(callbackRoutes[F](codes).orNotFound).build
+  /** The callback server on `host`; `port` is 7070 except in tests, since the registered redirect URI names it. */
+  def serverResource[F[_]: Async: Network](codes: SignInCodes[F], host: Host, port: Port = CallbackConfig.Port): Resource[F, org.http4s.server.Server] =
+    ???
 
-  def resource[F[_]: Async: Network](client: Client[F], creds: SpotifyCredentials, store: TokenStore[F]): Resource[F, SpotifyClient[F]] =
-    for
-      session <- Resource.eval(Ref.of[F, Option[SpotifySession]](None))
-      codes <- Resource.eval(SignInCodes.make[F])
-      _ <- serverResource(codes)
-    yield new SpotifyClient[F](client, creds, session, codes, store)
+  def resource[F[_]: Async: Network](client: Client[F], creds: SpotifyCredentials, store: TokenStore[F])(
+    callback: CallbackConfig
+  ): Resource[F, SpotifyClient[F]] =
+    ???
 
   object CodeMatcher extends QueryParamDecoderMatcher[String]("code")
diff --git a/src/main/scala/domain/config/AppConfig.scala b/src/main/scala/domain/config/AppConfig.scala
index 0ceaf7c..4173945 100644
--- a/src/main/scala/domain/config/AppConfig.scala
+++ b/src/main/scala/domain/config/AppConfig.scala
@@ -10,7 +10,8 @@ case class AppConfig(
   hue: HueConfig,
   pollingIntervalMs: Int = 2000,
   lightShow: LightShowConfig = LightShowConfig(),
-  ui: UiConfig = UiConfig()
+  ui: UiConfig = UiConfig(),
+  callback: CallbackConfig = CallbackConfig()
 ) derives ConfigReader
 object AppConfig {
   def load[F[_]: Sync](source: ConfigSource): F[AppConfig] =
```

## Slices

1. **Configurable callback host, Spotify-timing startup line, and a multi-arch Docker image.** Delivers everything in one PR, because the image depends on the new config key: the `callback.host` key (default 127.0.0.1), the Spotify-timing startup line, the Dockerfile with `.dockerignore` and `docker/application.conf`, the README's Docker section, and the updated docs on the callback server.
   - `docker build .` produces an image that starts the app when given only the README's environment variables.
   - From an amd64 machine with a `docker-container` builder, `docker buildx build --platform linux/amd64,linux/arm64 .` builds both images and compiles only once, in the build platform's stage. A plain `docker build` builds and runs both on amd64 and on an arm64 Raspberry Pi.
   - With `-p 127.0.0.1:7071:7071`, the settings page opens at http://127.0.0.1:7071 in a browser on the host.
   - With `-p 127.0.0.1:7070:7070` and no refresh token, the logged authorize URL completes sign-in through http://127.0.0.1:7070/callback, and the lights start syncing.
   - After a browser sign-in, recreating the container with the same named volume at /data starts the app without logging an authorize URL.
   - In the container, with no audio device, the app plays on Spotify timing and logs one startup line saying live beat detection is off because the beat source is Spotify. It does the same when the mounted /config/application.conf sets `beat-source = "audio"`.
   - With the default config, the sign-in callback listens on 127.0.0.1:7070 and refuses connections on the machine's non-loopback addresses.
   - With `callback.host = "0.0.0.0"`, the callback accepts GET /callback?code=… on a non-loopback address and hands the code to the waiting sign-in.
   - With beat-source spotify, the startup log has exactly one line saying live beat detection is off because the beat source is Spotify. With beat-source audio, it has none.
   - With `callback.host` set to something that isn't a host ("not a host!" or ""), the app exits with an error code before contacting the bridge, and the error log names `callback.host`.
   - The README shows `docker build .` and says it works on amd64 or arm64. It also shows `docker buildx create --use` (or says the containerd image store is needed) and `docker buildx build --platform linux/amd64,linux/arm64 --push -t <registry>/<image> .`, and says `--load` only works for a single platform.
   - The README shows a `docker run` with `--env-file .env`, `-p 127.0.0.1:7070:7070`, `-p 127.0.0.1:7071:7071`, and a named volume at /data that keeps the refresh token. It says to open http://127.0.0.1:<published port>, not the 0.0.0.0 address the log prints.
   - The README shows `ssh -L 7070:localhost:7070 <server>` for signing in on a headless server or Pi, and says setting SPOTIFY_REFRESH_TOKEN skips the browser sign-in instead.
   - The README shows publishing the settings port without 127.0.0.1 to open the page to the local network, and warns that the page has no sign-in, so anyone who can reach it can change the lights.
   - The README shows mounting your own application.conf read-only at /config/application.conf. It says the file should hold only the light-show lines from the settings page's "Copy as config", not a copy of the bundled application.conf, and that the image's container settings always win.
   - docs/how-it-works.md and docs/architecture.md say the callback server listens on port 7070 at `callback.host`, which defaults to 127.0.0.1, and that the image sets it to 0.0.0.0. reference.conf documents the key.
   - Tests: CallbackConfigSpec is a table over configs loaded with reference.conf. A missing key gives 127.0.0.1. "0.0.0.0" and "localhost" load. "not a host!", "" and "a b" give a Left whose prettyPrint names `callback.host`. A property checks that any generated IPv4 address reads back as the same Host.

SpotifyCallbackServerSpec (CatsEffectSuite) finds an IPv4 address in `IO.blocking`. The interface must be up, not loopback, not virtual and not link-local, and the address site-local or routable. If there is none, the test fails when `CI` is set and calls `assume` otherwise, and the PR confirms it ran in CI. The test starts `serverResource(codes, host, port"0")`. With the default host, a connection on that address is refused. With 0.0.0.0, GET /callback?code=c returns 200 and `codes` yields "c". The test also asserts that the port is 7070 and the default host is 127.0.0.1.

MainSpec uses the existing captured-log and fake-bridge harness. With spotify, exactly one Spotify-timing line is logged. With audio, none is. An invalid `callback.host` gives ExitCode.Error, no bridge connections, and an ERROR line naming the key. Nothing new is timed, so there's no TestControl.

The Docker criteria are checked by hand and recorded in the PR: the buildx build from amd64, and a plain build, run, sign-in and recreate on amd64 and on a Pi. Building images in CI is out of scope. The documentation criteria are met by the docs themselves.

## Alternatives

- Put the host on `SpotifyCredentials`. Why not: it derives a circe Codec and holds only credentials, so validation would move out of the config reader.
- Use sbt-native-packager or sbt-assembly. Why not: the issue rules out new sbt plugins, and `export` plus `java -cp` does the same job.
- Include the user's config after the container keys. Why not: a mounted copy of the bundled file would bring back `beat-source = "audio"` and the 127.0.0.1 hosts.