# ADR 0002: Save the newest Spotify refresh token in an owner-only file and start from it

Issue: LorenzoPrinsloo/spotify-hue-sync#9 · Status: proposed

## Context

Spotify can issue a new refresh token at browser sign-in or on renewal. The app keeps it only in a `Ref`, so a restart goes back to SPOTIFY_REFRESH_TOKEN, which may be stale, or to the browser sign-in. Home, Pi and Docker users have to sign in again after every restart.

## Decision

Add a `TokenStore[F]` (load / save / forget) that `SpotifyClient` calls, backed by one owner-only file. Sign-in order: saved token, then SPOTIFY_REFRESH_TOKEN, then the browser. On invalid_grant for the saved token: WARN, forget, next source. Every refresh token Spotify issues (code exchange or renewal) is saved. `renew`'s no-session branch goes through `signIn`, so the saved token wins there too.
A pure `TokenFile.locate` resolves the path: SPOTIFY_TOKEN_FILE, `spotify.token-file`, `$XDG_CONFIG_HOME/…`, `$HOME/.config/…`. Blank counts as unset. This is code, not HOCON `${?…}`, because in HOCON a later user line would beat the variable.
The file is read once, when the store is built, and `load` returns that result. So a broken file WARNs once per run, even when the poller retries `signIn`. A `Ref` of problem kinds already logged keeps save-side WARNs to one per run.
Saves are serialised by a `Mutex` (the renderer and poller can both renew on a 401). Each save writes a temp file in the same directory, created 0600, then moves it atomically over the target. If the move fails (a single-file bind mount), it truncates and writes in place. Then it reads the permissions back. The token is never on disk with loose permissions, and a crash can't leave a truncated file.
Permissions go through `FileModes[F]` (get/set). Whether they took is decided by reading them back, never by whether `set` threw, so a chmod that silently does nothing on vfat/CIFS is caught.

## Components and call direction

- `Main` → `TokenFile.locate(config.spotify.tokenFile, env)` → `TokenStore.at` (reads the file once) → `SpotifyClient.resource(…, store)`.
- `SpotifyClient.signIn` → `store.load` → `refreshWith(saved)`. On `Rejected` → `store.forget` → `fromConfigured` → `initialAuth`.
- `renew` with no session → `signIn`.
- `initialAuth`, and `refreshWith` when Spotify returns a `refresh_token` → `store.save`.
- `TokenStore.file` → fs2 `Files` (createDirectories 0700, createTempFile 0600, move, delete/truncate) and `FileModes` (read-back checks). `TokenFile.parse`/`render` are pure.
- `SyncEngine` and the settings page are unchanged.

## Contracts

- Config key `spotify.token-file` (optional string): additive. New field `SpotifyCredentials.tokenFile: Option[String] = None`; the circe codec gains an optional field (additive).
- Env `SPOTIFY_TOKEN_FILE` (optional, beats the key), read in `Main` via `cats.effect.std.Env`. `XDG_CONFIG_HOME` and `HOME` are read for the default only.
- File: the token plus one `\n`. Mode 0600; 0700 for a directory the app creates.
- `SpotifyClient.resource` gains `store` (`Main` is its only caller). The primary constructor gains `store`; the 4-argument constructors stay and use `TokenStore.none`.
- `application.conf` gets a commented `# token-file = "/data/refresh-token"`. README and .env.example are updated.
- No HTTP endpoint or settings JSON changes.

## Failure modes

Sealed `TokenFileProblem`: Unreadable, Empty, Malformed, LoosePermissions, NoPosixPermissions, Unwritable, Undeletable, NoLocation. Each is logged as a WARN, never raised. Nothing about the file is fatal.
- Load (once per run): missing → None, no log. Unreadable/Empty/Malformed → WARN, None. Group/other bits set → set 0600 and read back. If 0600 is confirmed: LoosePermissions WARN. If not, or on any exception: NoPosixPermissions WARN. Either way the token is used.
- Save: a failed mkdir, temp write and fallback write → Unwritable {path, reason}, once per run, and the token stays in memory. Mode not 0600 on read-back, or a get/set exception (incl. IOException/EPERM) → NoPosixPermissions, once per run; the file is still written.
- Forget: delete. If that fails, truncate to empty (read next start as Empty, so the rejected token is never sent again). Undeletable only if both fail. Clears the loaded token.
- No location → NoLocation WARN, `TokenStore.none`.
- Spotify: `Rejected` on the saved token is recovered (forget, fall back). `Failed` (429/5xx) propagates as today and the file is kept.

## Config

- `spotify.token-file`: no default in reference.conf. When it's unset: `$XDG_CONFIG_HOME/spotify-hue-sync/refresh-token`, else `$HOME/.config/spotify-hue-sync/refresh-token`.
- `SPOTIFY_TOKEN_FILE` overrides it; blank counts as unset.
- Resolved once at startup. It never refuses to start: with no location, the app warns and keeps the token in memory.

## Observability

MDC fields are in braces. No line contains a token. `reason` is the exception's class and message, never file contents.
- INFO "Spotify refresh token file" {path}, at startup.
- INFO "Signed in to Spotify with the saved refresh token" {client_id}.
- WARN "Spotify rejected the saved refresh token (status: …); deleted it and falling back" {client_id}.
- WARN "Couldn't read the saved refresh token" {path, reason}; "…is empty" / "…is malformed" {path}.
- WARN "The refresh token file was readable by others; restricted it to 0600" {path}, only after the read-back confirms it.
- WARN "Couldn't restrict the refresh token file's permissions on this filesystem" {path}.
- WARN "Couldn't save the refresh token" {path, reason}.
- WARN "Couldn't delete or empty the rejected refresh token file" {path, reason}.
- WARN "No location for the refresh token file; keeping it in memory".

## Program design

`src/main/scala/client/TokenFileProblem.scala` (new):

```scala
package client

import fs2.io.file.Path

/** Why the refresh token file couldn't be used as it is. Messages name the file and the reason, never the token. */
enum TokenFileProblem derives CanEqual {
  case Unreadable(path: Path, reason: String)
  case Empty(path: Path)
  case Malformed(path: Path)
  case LoosePermissions(path: Path)
  case NoPosixPermissions(path: Path)
  case Unwritable(path: Path, reason: String)
  case Undeletable(path: Path, reason: String)
  case NoLocation

  def message: String = ???
}
```

`src/main/scala/client/TokenFile.scala` (new):

```scala
package client

import fs2.io.file.Path

/** Where the refresh token file lives and what it holds. */
object TokenFile {

  val EnvVar = "SPOTIFY_TOKEN_FILE"

  /**
   * SPOTIFY_TOKEN_FILE, then `spotify.token-file`, then `$XDG_CONFIG_HOME/spotify-hue-sync/refresh-token`, then
   * `$HOME/.config/spotify-hue-sync/refresh-token`. Blank values count as unset.
   */
  def locate(configured: Option[String], env: Map[String, String]): Option[Path] = ???

  /** The token in `contents`: one line of printable characters with no spaces, and an optional trailing newline. */
  def parse(path: Path, contents: String): Either[TokenFileProblem, String] = ???

  def render(token: String): String = ???
}
```

`src/main/scala/client/FileModes.scala` (new):

```scala
package client

import fs2.io.file.{Files, Path, PosixPermissions}

/** Reads and sets a file's POSIX permissions. Whether a `set` took effect is only known by reading them back. */
trait FileModes[F[_]] {
  def get(path: Path): F[PosixPermissions]
  def set(path: Path, permissions: PosixPermissions): F[Unit]
}

object FileModes {

  def posix[F[_]: Files]: FileModes[F] = ???
}
```

`src/main/scala/client/TokenStore.scala` (new):

```scala
package client

import cats.Applicative
import cats.effect.Async
import fs2.io.file.{Files, Path}

/** The newest refresh token Spotify issued, kept between runs. Problems are logged as WARNs, never raised. */
trait TokenStore[F[_]] {

  /** The token the file held when the store was built, or None when there was none, it couldn't be used or it was forgotten. */
  def load: F[Option[String]]

  /** Atomically replaces the file with `token`, created owner-only, creating its directory owner-only if needed. */
  def save(token: String): F[Unit]

  /** Deletes the saved token, or empties the file when it can't be deleted. */
  def forget: F[Unit]
}

object TokenStore {

  /** Keeps nothing between runs. */
  def none[F[_]: Applicative]: TokenStore[F] = ???

  /** The file at `location`, or `none` with a WARN when there is no location. */
  def at[F[_]: Async: Files](location: Option[Path]): F[TokenStore[F]] = ???

  /**
   * Reads the file at `path` once, tightening loose permissions through `modes`. Each kind of problem from saving or
   * restricting permissions is logged at most once per store.
   */
  def file[F[_]: Async: Files](path: Path, modes: FileModes[F]): F[TokenStore[F]] = ???
}
```

Changes to existing files:

```diff
diff --git a/src/main/scala/Main.scala b/src/main/scala/Main.scala
index bf2a128..5c5e8a1 100644
--- a/src/main/scala/Main.scala
+++ b/src/main/scala/Main.scala
@@ -1,7 +1,8 @@
 import cats.effect.*
 import cats.effect.{ExitCode, IOApp}
 import cats.effect.kernel.Resource
-import client.{DtlsEmitter, EntertainmentStream, HttpClients, HueClient, SpotifyClient}
+import cats.effect.std.Env
+import client.{DtlsEmitter, EntertainmentStream, HttpClients, HueClient, SpotifyClient, TokenFile, TokenStore}
 import cats.syntax.all.*
 import beat.{BeatTracking, TrackerSettings}
 import domain.config.{AppConfig, BeatSource}
@@ -28,7 +29,9 @@ object Main extends IOApp {
       config <- Resource.eval(AppConfig.load[IO](source))
       clients <- HttpClients.resource[IO]
 
-      spotify <- SpotifyClient.resource[IO](clients.spotify, config.spotify)
+      env <- Resource.eval(Env[IO].entries.map(_.toMap))
+      store <- Resource.eval(TokenStore.at[IO](TokenFile.locate(config.spotify.tokenFile, env)))
+      spotify <- SpotifyClient.resource[IO](clients.spotify, config.spotify, store)
       hueClient = new HueClient[IO](config.hue.creds, clients.bridge)
       emitter = new DtlsEmitter[IO](config.hue.creds)
 
diff --git a/src/main/scala/client/SpotifyClient.scala b/src/main/scala/client/SpotifyClient.scala
index 1a2d92f..31a9d63 100644
--- a/src/main/scala/client/SpotifyClient.scala
+++ b/src/main/scala/client/SpotifyClient.scala
@@ -33,12 +33,17 @@ class SpotifyClient[F[_]: Async: Network](
   client: Client[F],
   creds: SpotifyCredentials,
   session: Ref[F, Option[SpotifySession]],
-  codes: SignInCodes[F]
+  codes: SignInCodes[F],
+  store: TokenStore[F]
 ) extends Http4sClientDsl[F]:
 
+  /** Keeps the refresh token in memory only. */
+  def this(client: Client[F], creds: SpotifyCredentials, session: Ref[F, Option[SpotifySession]], codes: SignInCodes[F]) =
+    this(client, creds, session, codes, TokenStore.none[F])
+
   /** Signs in through the browser at most once, with the code `authCode` is completed with. */
   def this(client: Client[F], creds: SpotifyCredentials, session: Ref[F, Option[SpotifySession]], authCode: Deferred[F, String]) =
-    this(client, creds, session, SignInCodes.once[F](authCode))
+    this(client, creds, session, SignInCodes.once[F](authCode), TokenStore.none[F])
 
   private val authUri = uri"https://accounts.spotify.com/api/token"
   private val apiUri = uri"https://api.spotify.com/v1"
@@ -63,56 +68,19 @@ class SpotifyClient[F[_]: Async: Network](
       }
 
   /**
-   * Logs the authorize URL, waits for the browser to bring the code back to the callback server, and keeps the refresh
-   * token Spotify sends with the exchange.
+   * Logs the authorize URL, waits for the browser to bring the code back to the callback server, and keeps and saves
+   * the refresh token Spotify sends with the exchange.
    */
-  def initialAuth: F[String] =
-    val redirectUri = "http://127.0.0.1:7070/callback"
-    val scopes = List("user-read-playback-state", "user-read-currently-playing").mkString("%20")
-    val state = s"random_${random.nextLong()}"
-    val authUrl =
-      s"https://accounts.spotify.com/authorize?client_id=${creds.clientId}&response_type=code&redirect_uri=$redirectUri&scope=$scopes&state=$state"
-
-    for
-      waitForCode <- codes.attempt
-      _ <- logger.info(s"Please visit: $authUrl")
-      code <- waitForCode
-      authHeader = Authorization(BasicCredentials(creds.clientId, creds.clientSecret))
-      form = UrlForm(
-        "grant_type" -> "authorization_code",
-        "code" -> code,
-        "redirect_uri" -> redirectUri
-      )
-      req = Request[F](Method.POST, authUri)
-        .withEntity(form)
-        .putHeaders(authHeader)
-      resp <- naming("Code Exchange")(client.expectOr[SpotifyToken](req) { errorRes =>
-        errorRes.as[Json].flatMap { body =>
-          logger.error(s"Spotify Exchange Failed. Body: $body") *>
-            Async[F].raiseError(new RuntimeException(s"Spotify Error ${errorRes.status}: $body"))
-        }
-      })
-      refresh <- resp.refresh_token.fold(Async[F].raiseError[String](new RuntimeException("Spotify sent no refresh token with the code exchange")))(
-        Async[F].pure
-      )
-      _ <- session.set(Some(SpotifySession(resp.access_token, refresh)))
-      _ <- logger.info(Map("client_id" -> creds.clientId))("Signed in to Spotify through the browser")
-    yield resp.access_token
+  def initialAuth: F[String] = ???
 
   /**
-   * Starts a session from the configured refresh token, falling back to the browser sign-in when there is none or
-   * Spotify answers that it's no good (invalid_grant).
+   * Starts a session from the saved refresh token, then the configured one, then the browser sign-in. A saved token
+   * Spotify rejects (invalid_grant) is forgotten.
    */
-  def signIn: F[String] =
-    creds.configuredRefreshToken.fold(initialAuth) { token =>
-      refreshWith("Refresh Token Exchange", token)
-        .flatTap(_ => logger.info(Map("client_id" -> creds.clientId))("Signed in to Spotify with the configured refresh token"))
-        .recoverWith { case SpotifyTokenError.Rejected(_, status, _) =>
-          logger.warn(Map("client_id" -> creds.clientId))(
-            s"Spotify rejected the configured refresh token (status: $status); signing in through the browser instead"
-          ) *> initialAuth
-        }
-    }
+  def signIn: F[String] = ???
+
+  /** The configured refresh token, falling back to the browser sign-in when there is none or Spotify rejects it. */
+  private def fromConfigured: F[String] = ???
 
   /**
    * Helper to always get a valid token.
@@ -130,27 +98,16 @@ class SpotifyClient[F[_]: Async: Network](
     for
       token <- session.get.flatMap {
         case Some(s) => refreshWith("Renew", s.refreshToken)
-        case None => creds.configuredRefreshToken.fold(initialAuth)(refreshWith("Renew", _))
+        case None => signIn
       }
       _ <- logger.info("Renewed the Spotify access token")
     yield token
 
   /**
-   * Swaps `refresh` for a new access token and stores the session, keeping `refresh` if Spotify sends no new one.
-   * A failure is a `SpotifyTokenError` named `call`.
+   * Swaps `refresh` for a new access token and stores the session, keeping `refresh` if Spotify sends no new one and
+   * saving the new one if it does. A failure is a `SpotifyTokenError` named `call`.
    */
-  private def refreshWith(call: String, refresh: String): F[String] =
-    val req = Request[F](Method.POST, authUri)
-      .withEntity(UrlForm("grant_type" -> "refresh_token", "refresh_token" -> refresh))
-      .putHeaders(Authorization(BasicCredentials(creds.clientId, creds.clientSecret)))
-    for
-      resp <- naming(call)(client.expectOr[SpotifyToken](req) { errorRes =>
-        errorRes.bodyText.compile.string.flatMap { body =>
-          Async[F].raiseError(SpotifyTokenError.of(call, errorRes.status, body))
-        }
-      })
-      _ <- session.set(Some(SpotifySession(resp.access_token, resp.refresh_token.getOrElse(refresh))))
-    yield resp.access_token
+  private def refreshWith(call: String, refresh: String): F[String] = ???
 
   /**
    * Sends `request` with the access token. If Spotify rejects the token, renews it and sends the request once more,
@@ -203,11 +160,11 @@ object SpotifyClient:
   def serverResource[F[_]: Async: Network](codes: SignInCodes[F]): Resource[F, org.http4s.server.Server] =
     EmberServerBuilder.default[F].withHost(ipv4"127.0.0.1").withPort(port"7070").withHttpApp(callbackRoutes[F](codes).orNotFound).build
 
-  def resource[F[_]: Async: Network](client: Client[F], creds: SpotifyCredentials): Resource[F, SpotifyClient[F]] =
+  def resource[F[_]: Async: Network](client: Client[F], creds: SpotifyCredentials, store: TokenStore[F]): Resource[F, SpotifyClient[F]] =
     for
       session <- Resource.eval(Ref.of[F, Option[SpotifySession]](None))
       codes <- Resource.eval(SignInCodes.make[F])
       _ <- serverResource(codes)
-    yield new SpotifyClient[F](client, creds, session, codes)
+    yield new SpotifyClient[F](client, creds, session, codes, store)
 
   object CodeMatcher extends QueryParamDecoderMatcher[String]("code")
diff --git a/src/main/scala/domain/spotify/SpotifyCredentials.scala b/src/main/scala/domain/spotify/SpotifyCredentials.scala
index e18690e..c3309b0 100644
--- a/src/main/scala/domain/spotify/SpotifyCredentials.scala
+++ b/src/main/scala/domain/spotify/SpotifyCredentials.scala
@@ -9,7 +9,8 @@ import io.circe.Codec
 case class SpotifyCredentials(
   clientId: String,
   clientSecret: String,
-  refreshToken: Option[String] = None
+  refreshToken: Option[String] = None,
+  tokenFile: Option[String] = None
 ) derives Codec.AsObject {
 
   /** The refresh token to start from, if one is configured: `SPOTIFY_REFRESH_TOKEN=` left empty in `.env` counts as none. */
```

## Slices

1. **Save the refresh token in an owner-only file and start from it.** Spotify sign-in survives restarts. The newest refresh token is saved atomically at a configurable path and beats SPOTIFY_REFRESH_TOKEN. A rejected or broken file falls back to the old order. README and .env.example document it.
   - After one browser sign-in against a fake Spotify, a second client on the same token file with no SPOTIFY_REFRESH_TOKEN signs in with one refresh_token call and logs no 'Please visit' line.
   - When a renewal answers with a new refresh_token, the file then holds it, and the next client's first token call sends it.
   - With a saved token and a different SPOTIFY_REFRESH_TOKEN, the first token call sends the saved token, including when `renew` is the first call.
   - With no token-file setting, the file is at $XDG_CONFIG_HOME/spotify-hue-sync/refresh-token, or $HOME/.config/spotify-hue-sync/refresh-token when XDG_CONFIG_HOME is unset.
   - With spotify.token-file or SPOTIFY_TOKEN_FILE set, the file is saved and read at that path, and SPOTIFY_TOKEN_FILE wins.
   - The saved file's contents are exactly the token followed by one newline, even after concurrent saves.
   - A token file the app creates has mode 0600 from creation, and a directory it creates has mode 0700.
   - An existing 0644 token file ends up 0600, the app logs one WARN, and its token is sent to Spotify.
   - When permissions can't be set, whether `set` throws or silently does nothing, the file is still written, the app logs one WARN that it couldn't restrict them, and it never logs the 'restricted it to 0600' WARN.
   - When Spotify answers the saved token with 400 invalid_grant, the app logs a WARN that the saved refresh token was rejected and removes the file (or empties it if it can't be deleted). It then sends SPOTIFY_REFRESH_TOKEN, or opens the browser sign-in, and a later start never sends the rejected token.
   - An unreadable, empty or malformed file gives one WARN per run, even when the configured token then gets 503 twice and sign-in is retried. A missing file gives no WARN. The app falls back to SPOTIFY_REFRESH_TOKEN or the browser and keeps running.
   - When the token can't be written, the app logs exactly one WARN naming the file and the reason across several renewals, and playback polls keep succeeding.
   - No log event at any level, in any of these scenarios, contains the refresh token's value.
   - The README says where the refresh token is saved and how to move it with spotify.token-file or SPOTIFY_TOKEN_FILE. It says the saved token beats SPOTIFY_REFRESH_TOKEN, and that deleting the file falls back to SPOTIFY_REFRESH_TOKEN and then the browser sign-in, which is how to switch token or account.
   - .env.example lists SPOTIFY_TOKEN_FILE as optional, with its default ~/.config/spotify-hue-sync/refresh-token (or under $XDG_CONFIG_HOME).
   - Tests: `TokenFile.locate` gets a table over env, config and blank values. `parse`/`render` get a ScalaCheck round-trip property plus a table of empty and malformed contents.
`TokenStore.file` runs in a `Files.tempDirectory` with fs2 `Files`. It checks the created modes, tightening 0644, delete, a truncate fallback when delete fails, a parent path that is a regular file (unwritable even as root), a token path that is a directory (unreadable even as root), and parallel saves leaving one well-formed file. `FileModes` fakes cover a `set` that does nothing and one that throws IOException.
`SpotifyClientSpec` uses a fake Spotify over HTTP that records each refresh_token sent. Two clients share a file to simulate restarts: browser sign-in, rotation, saved beats configured, `renew` first, invalid_grant fallback, and 503 twice with a malformed file. `TestControl` covers the browser wait and retries.
WARN counts come from a logback ListAppender. A ScalaCheck property over generated tokens runs every scenario and asserts that no event's message or MDC contains the token.

## Alternatives

- HOCON `token-file = ${?SPOTIFY_TOKEN_FILE}`: a user's later `spotify.token-file` line would beat the env var, which breaks AC 5.
- Write in place, then chmod: the file has umask permissions until the chmod runs (and keeps them if it fails), and a crash or two concurrent renewals can leave a truncated or interleaved file.
- Treat a `set` exception as the only sign of no POSIX support: on vfat/CIFS chmod often does nothing without an error, so the WARN would never fire and every start would log a false 'restricted' WARN.