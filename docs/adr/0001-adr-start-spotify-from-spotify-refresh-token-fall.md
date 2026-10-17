# ADR 0001: ADR: Start Spotify from SPOTIFY_REFRESH_TOKEN, fall back to a retryable browser sign-in, drop the sp_dc cookie

Issue: LorenzoPrinsloo/spotify-hue-sync#8 · Status: proposed

## Context

`SpotifyClient.initialAuth` requests the authorize URL with `Cookie: sp_dc=$SPOTIFY_SP_DC` (`src/main/scala/client/SpotifyClient.scala:73`). That cookie is a full web session, and it's the only reason a start finishes without a browser. `getOrRefresh` always goes to `initialAuth` when there is no session, so the configured refresh token is never used at startup. `application.conf:4` makes `SPOTIFY_REFRESH_TOKEN` required. The code reaches the client through a single `Deferred` (`:176`). After the first callback it stays completed, so a failed code exchange repeats on every poll until the app is restarted. With the cookie gone, the browser is the only fallback, so that has to be fixed too.

## Decision

Sign-in stays lazy, at the first Spotify call, so the poll loop and its retry don't change. The `None` branch of `getOrRefresh` calls a new `signIn`:
- **Startup swap**: if `creds.configuredRefreshToken` (trimmed, `None` when blank) is set, `refreshWith("Refresh Token Exchange", token)` posts `grant_type=refresh_token`. Only Spotify's answer about the token itself, **400 with `error: invalid_grant`**, becomes `SpotifyTokenError.Rejected`. That logs a token-free WARN, then runs `initialAuth`. Anything else (429, `invalid_client`, other 4xx, 5xx, transport errors, TLS) is `Failed` or propagates. That poll fails, and the next tick tries the swap again. These failures say nothing about the token, so the user isn't sent to a browser for them.
- **Browser sign-in**: `initialAuth` no longer requests `/authorize` and no longer reads `SPOTIFY_SP_DC`. It calls `codes.attempt`, which opens a fresh wait unless one is still pending, then logs the URL and waits for the code. A failed exchange spends that attempt, so the next tick logs a new URL that can actually be approved. `callbackRoutes` hands the code to `SignInCodes.hand`. The route, port and response stay the same.
- **Session after a browser sign-in**: it stores `resp.refresh_token` directly, never the configured token, which may be blank or just rejected. Spotify always sends a refresh token on the authorization-code grant. If one were missing, the session would hold `""`, and the next renew would fail that poll, the same as a revoked token does today.
- `renew` keeps its current behaviour and goes through `refreshWith("Renew", …)`.
- `SpotifyClient` and `callbackRoutes` keep an overload that takes a `Deferred`, wrapped by `SignInCodes.once`, as a single attempt. Existing callers and tests compile unchanged.
- Order: contracts first, then the retry fix and the startup swap, and only then is the cookie removed. No release makes every start wait on a browser.

## Components and call direction

- `Main` → `SpotifyClient.resource` (now builds `SignInCodes.make`) → `serverResource(codes)` → `callbackRoutes(codes)` → `codes.hand`.
- `SyncEngine` poller → `getPlaybackState` → `authorised` → `getOrRefresh` → **`signIn`** → `refreshWith("Refresh Token Exchange")` | **`initialAuth`** → `codes.attempt` → `POST accounts.spotify.com/api/token`.
- `renew` → `refreshWith("Renew")`.
- New: `client.SignInCodes` (one code per attempt), and `client.SpotifyTokenError` (sealed) with the pure classifier `of`.
- `domain.spotify.SpotifyCredentials` gains the pure `configuredRefreshToken`.
- Docs: README, `.env.example` and `docs/how-it-works.md` §1. Build: `Test / fork` and `Test / envVars`.

## Contracts

- Env `SPOTIFY_REFRESH_TOKEN`: required → optional; unset or blank means no token. Additive, because it only loosens.
- Env `SPOTIFY_SP_DC`: no longer read. Removed from `.env.example` and the docs, and any value left in `.env` is ignored. Removal of an optional input.
- Config `spotify.refresh-token`: `refresh-token = ""` followed by `refresh-token = ${?SPOTIFY_REFRESH_TOKEN}`. Additive.
- `SpotifyCredentials`: `refreshToken` defaults to `""`, and adds `configuredRefreshToken: Option[String]`. The circe shape is unchanged. Additive.
- New `SpotifyTokenError` (a `RuntimeException`) and new `SignInCodes`. Additive.
- `SpotifyClient`: adds `signIn`. The primary constructor and `callbackRoutes`/`serverResource` now take `SignInCodes[F]`. The `Deferred` overloads remain, so the change is source-compatible. The `"Authorize"` call name goes away and `"Refresh Token Exchange"` is new.
- Outbound HTTP: no `GET /authorize` and no `Cookie` header. Adds one `POST /api/token grant_type=refresh_token` when a token is configured.
- Unchanged: settings JSON, the UI endpoints, the callback route and its port, the scopes.

## Failure modes

Carried by `SpotifyTokenError`: `Rejected(call, status, body)` only for 400 with `error=invalid_grant`, and `Failed(call, status, body)` for every other unsuccessful answer.
- **Startup swap**: `Rejected` is recoverable. It logs a WARN, then the browser sign-in runs. `Failed` (429, `invalid_client`, 5xx), transport errors and `SpotifyTlsFailure("Refresh Token Exchange")` fail that poll. The session stays `None`, so the next tick tries the swap again. A wrong client id or secret therefore shows up as a repeated ERROR that names `invalid_client`, and never as a browser loop.
- **Browser sign-in**: a failed code exchange (an expired code, a double click, a 5xx) fails that poll and spends the attempt. The next tick opens a new attempt and logs a new URL, and approving that URL completes sign-in. A callback that arrives with no attempt waiting returns the same page, and the code is dropped. Waiting for approval never times out, which is intended.
- **Renew during a run**: either error fails that poll, as it does today.
- Nothing here is fatal at startup. A missing or blank token can no longer cause a config error.
- Secrets: no token value appears in any message or log line. `body` is Spotify's error JSON, which doesn't echo the token. Never log `SpotifyCredentials` or `AppConfig`.

## Config

No new settings. `spotify.refresh-token` becomes optional (`""` unless `SPOTIFY_REFRESH_TOKEN` is set). pureconfig still requires `client-id` and `client-secret`. A blank or whitespace-only token counts as absent rather than invalid, so `SPOTIFY_REFRESH_TOKEN=` copied from `.env.example` works. `LightShowConfig.problems` is untouched.

## Observability

All from the `client.SpotifyClient` logger:
- INFO `Signed in to Spotify with the configured refresh token`. No fields.
- WARN `Spotify rejected the configured refresh token (status: <status>); signing in through the browser instead`. Fields: the status only, never the token or the body.
- INFO `Please visit: <authorize URL>` (text unchanged). Fields: `client_id`, `redirect_uri`, `scope`, `state`. Logged once per attempt.
- INFO `Signed in to Spotify through the browser`, after a successful code exchange.
- Unchanged: ERROR `Spotify Exchange Failed. Body: <body>`, INFO `Renewed the Spotify access token`, and ERROR `Spotify Poll Failed: <error>` from `SyncEngine`, which carries the call, the status and Spotify's body.

## Program design

`src/main/scala/client/SpotifyTokenError.scala` (new):

```scala
package client

import org.http4s.Status

/** Spotify's token endpoint refused a request. Messages carry Spotify's answer, never the token sent. */
sealed abstract class SpotifyTokenError(message: String) extends RuntimeException(message)

object SpotifyTokenError {

  /** Spotify's answer that the grant itself is no good: 400 with `error: invalid_grant`. */
  final case class Rejected(call: String, status: Status, body: String) extends SpotifyTokenError(s"Spotify rejected the $call, $status: $body")

  /** Any other unsuccessful answer, such as 429, `invalid_client` or Spotify being down: says nothing about the token. */
  final case class Failed(call: String, status: Status, body: String) extends SpotifyTokenError(s"Spotify $call failed, $status: $body")

  def of(call: String, status: Status, body: String): SpotifyTokenError = ???
}
```

`src/main/scala/client/SignInCodes.scala` (new):

```scala
package client

import cats.Applicative
import cats.effect.{Concurrent, Deferred}

/** Carries the code from the callback server to the browser sign-in waiting for it, one code per attempt. */
trait SignInCodes[F[_]] {

  /** Opens an attempt, or joins the one still waiting, and gives the wait for its code. A spent attempt is never reused. */
  def attempt: F[F[String]]

  /** Hands over a code from the callback: false if no attempt was waiting for one. */
  def hand(code: String): F[Boolean]
}

object SignInCodes {

  def make[F[_]: Concurrent]: F[SignInCodes[F]] = ???

  /** A single attempt that `deferred` completes. */
  def once[F[_]: Applicative](deferred: Deferred[F, String]): SignInCodes[F] = ???
}
```

Changes to existing files:

```diff
diff --git a/src/main/scala/client/SpotifyClient.scala b/src/main/scala/client/SpotifyClient.scala
index a2a0ba4..a6e8aad 100644
--- a/src/main/scala/client/SpotifyClient.scala
+++ b/src/main/scala/client/SpotifyClient.scala
@@ -12,7 +12,6 @@ import org.http4s.headers.Authorization
 import org.http4s.client.dsl.Http4sClientDsl
 import org.http4s.ember.server.EmberServerBuilder
 import org.http4s.implicits.*
-import org.typelevel.ci.CIStringSyntax
 import org.typelevel.log4cats.slf4j.Slf4jLogger
 import com.comcast.ip4s.{ipv4, port}
 
@@ -34,9 +33,13 @@ class SpotifyClient[F[_]: Async: Network](
   client: Client[F],
   creds: SpotifyCredentials,
   session: Ref[F, Option[SpotifySession]],
-  authCodeDeferred: Deferred[F, String]
+  codes: SignInCodes[F]
 ) extends Http4sClientDsl[F]:
 
+  /** Signs in through the browser at most once, with the code `authCode` is completed with. */
+  def this(client: Client[F], creds: SpotifyCredentials, session: Ref[F, Option[SpotifySession]], authCode: Deferred[F, String]) =
+    this(client, creds, session, SignInCodes.once[F](authCode))
+
   private val authUri = uri"https://accounts.spotify.com/api/token"
   private val apiUri = uri"https://api.spotify.com/v1"
 
@@ -59,65 +62,31 @@ class SpotifyClient[F[_]: Async: Network](
         case _ => false
       }
 
-  def initialAuth: F[String] =
-    val redirectUri = "http://127.0.0.1:7070/callback"
-    val scopes = List("user-read-playback-state", "user-read-currently-playing").mkString("%20")
-    val state = s"random_${random.nextLong()}"
-    val authUrl =
-      s"https://accounts.spotify.com/authorize?client_id=${creds.clientId}&response_type=code&redirect_uri=$redirectUri&scope=$scopes&state=$state"
+  /**
+   * Starts a session from the configured refresh token, falling back to the browser sign-in when there is none or
+   * Spotify answers that it's no good (invalid_grant).
+   */
+  def signIn: F[String] = ???
 
-    for
-      _ <- logger.info(s"Please visit: $authUrl")
-      // With a valid session cookie this lands on the callback with the code; otherwise it's Spotify's login page, and
-      // the code comes when the authorize URL is opened in a browser. Either way the page itself isn't needed.
-      _ <- naming("Authorize")(client.run(Request[F](Method.GET, Uri.unsafeFromString(authUrl)).putHeaders(Header.Raw(ci"Cookie",
-              s"sp_dc=${sys.env.getOrElse("SPOTIFY_SP_DC", "")}")))
-          .use(_.body.compile.drain))
-      code <- authCodeDeferred.get
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
-      _ <- session.set(Some(SpotifySession(resp.access_token, resp.refresh_token.getOrElse(creds.refreshToken))))
-    yield resp.access_token
+  /**
+   * Logs the authorize URL and waits for the browser to bring the code back to the callback server. Keeps the refresh
+   * token Spotify sends with the new session.
+   */
+  def initialAuth: F[String] = ???
 
   /**
    * Helper to always get a valid token.
    */
-  def getOrRefresh: F[String] =
-    session.get.flatMap:
-      case Some(s) => Async[F].pure(s.accessToken)
-      case None => initialAuth
+  def getOrRefresh: F[String] = ???
 
   /**
    * Swaps the refresh token for a new access token, keeping the refresh token Spotify sends back, if any.
    * Spotify's access tokens expire after an hour.
    */
-  def renew: F[String] =
-    for
-      refresh <- session.get.map(_.fold(creds.refreshToken)(_.refreshToken))
-      req = Request[F](Method.POST, authUri)
-        .withEntity(UrlForm("grant_type" -> "refresh_token", "refresh_token" -> refresh))
-        .putHeaders(Authorization(BasicCredentials(creds.clientId, creds.clientSecret)))
-      resp <- naming("Renew")(client.expectOr[SpotifyToken](req) { errorRes =>
-        errorRes.bodyText.compile.string.flatMap { body =>
-          Async[F].raiseError(new RuntimeException(s"Couldn't renew the Spotify access token, ${errorRes.status}: $body"))
-        }
-      })
-      _ <- session.set(Some(SpotifySession(resp.access_token, resp.refresh_token.getOrElse(refresh))))
-      _ <- logger.info("Renewed the Spotify access token")
-    yield resp.access_token
+  def renew: F[String] = ???
+
+  /** Swaps `refresh` for an access token and stores the new session. Raises [[SpotifyTokenError]] if Spotify refuses. */
+  private def refreshWith(call: String, refresh: String): F[String] = ???
 
   /**
    * Sends `request` with the access token. If Spotify rejects the token, renews it and sends the request once more,
@@ -157,24 +126,21 @@ class SpotifyClient[F[_]: Async: Network](
 
 object SpotifyClient:
 
-  /** Where Spotify sends the browser back after sign-in: hands the code it carries to `deferred`. */
-  def callbackRoutes[F[_]: Async](deferred: Deferred[F, String]): HttpRoutes[F] = {
-    val dsl = new org.http4s.dsl.Http4sDsl[F] {}
-    import dsl.*
-    HttpRoutes.of[F] {
-      case GET -> Root / "callback" :? CodeMatcher(code) =>
-        deferred.complete(code) *> Ok("Success! Return to your terminal.")
-    }
-  }
+  /** Where Spotify sends the browser back after sign-in: hands the code it carries to the sign-in waiting for it. */
+  def callbackRoutes[F[_]: Async](codes: SignInCodes[F]): HttpRoutes[F] = ???
+
+  /** The callback for a single sign-in that `deferred` is completed by. */
+  def callbackRoutes[F[_]: Async](deferred: Deferred[F, String]): HttpRoutes[F] =
+    callbackRoutes[F](SignInCodes.once[F](deferred))
 
-  def serverResource[F[_]: Async: Network](deferred: Deferred[F, String]): Resource[F, org.http4s.server.Server] =
-    EmberServerBuilder.default[F].withHost(ipv4"127.0.0.1").withPort(port"7070").withHttpApp(callbackRoutes(deferred).orNotFound).build
+  def serverResource[F[_]: Async: Network](codes: SignInCodes[F]): Resource[F, org.http4s.server.Server] =
+    EmberServerBuilder.default[F].withHost(ipv4"127.0.0.1").withPort(port"7070").withHttpApp(callbackRoutes[F](codes).orNotFound).build
 
   def resource[F[_]: Async: Network](client: Client[F], creds: SpotifyCredentials): Resource[F, SpotifyClient[F]] =
     for
       session <- Resource.eval(Ref.of[F, Option[SpotifySession]](None))
-      authCodeDeferred <- Resource.eval(Deferred[F, String])
-      _ <- serverResource(authCodeDeferred)
-    yield new SpotifyClient[F](client, creds, session, authCodeDeferred)
+      codes <- Resource.eval(SignInCodes.make[F])
+      _ <- serverResource(codes)
+    yield new SpotifyClient[F](client, creds, session, codes)
 
   object CodeMatcher extends QueryParamDecoderMatcher[String]("code")
diff --git a/src/main/scala/domain/spotify/SpotifyCredentials.scala b/src/main/scala/domain/spotify/SpotifyCredentials.scala
index e17547e..3df401c 100644
--- a/src/main/scala/domain/spotify/SpotifyCredentials.scala
+++ b/src/main/scala/domain/spotify/SpotifyCredentials.scala
@@ -9,5 +9,9 @@ import io.circe.Codec
 case class SpotifyCredentials(
   clientId: String,
   clientSecret: String,
-  refreshToken: String
-) derives Codec.AsObject
+  refreshToken: String = ""
+) derives Codec.AsObject {
+
+  /** The refresh token to start from, if one is configured: `SPOTIFY_REFRESH_TOKEN=` left empty in `.env` counts as none. */
+  def configuredRefreshToken: Option[String] = ???
+}
```

## Slices

1. **1. Contracts: optional SPOTIFY_REFRESH_TOKEN, SpotifyTokenError, SignInCodes.** `application.conf` gives `refresh-token` a default of `""`, overridden by `${?SPOTIFY_REFRESH_TOKEN}`. Adds the `SpotifyCredentials` default and `configuredRefreshToken`, `SpotifyTokenError` with `of`, and the `SignInCodes` type, with `SpotifyClient` keeping its `Deferred` overloads. `renew` raises `SpotifyTokenError` through `refreshWith`. With no token set, the app starts without a config error and signs in through the existing flow.
   - The shipped application.conf loads with SPOTIFY_REFRESH_TOKEN set (refreshToken = "r"), empty ("") and unset (""), without reading the process environment
   - `configuredRefreshToken` is None for an empty or whitespace-only token, and otherwise is the trimmed token
   - `of` gives Rejected only for 400 with error=invalid_grant. It gives Failed for 429, for 400 and 401 with invalid_client, and for every other unsuccessful status. No message contains the refresh token sent
   - The existing renewal tests pass unchanged: polls keep succeeding after an expired access token is rejected (AC 7)
   - Tests: In LightShowConfigSpec, the shipped-conf test becomes a table. The set and empty rows replace `${?SPOTIFY_REFRESH_TOKEN}` explicitly and assert `spotify.refreshToken`. The unset row resolves with `ConfigResolveOptions.noSystem`. ScalaCheck properties cover `configuredRefreshToken` (blank vs non-blank) and `of` (generated statuses × error codes), plus a table with rows for 429, 400 invalid_client, 401 invalid_client, 400 invalid_grant and 503. A further property checks that the message of a renew failure never contains the generated refresh token. SpotifyClientSpec's renewal tests stay as they are.
2. **2. A failed browser sign-in can be retried.** `SignInCodes.make` holds one pending attempt, and a spent one is replaced. `initialAuth` opens an attempt before it logs the URL, and `callbackRoutes` hands the code to the attempt that's waiting. After a failed code exchange, the next poll logs a new URL whose approval completes sign-in, without a restart.
   - After a failed code exchange, a second 'Please visit' is logged on the next poll, and handing a second code via /callback leads to a successful poll
   - The second poll is still waiting until the second code arrives: it doesn't reuse the first code
   - /callback with no sign-in waiting still answers the same page, and the code is dropped
   - Existing callback and sign-in tests pass unchanged through the Deferred overloads
   - Tests: In SpotifyClientSpec, use `SignInCodes.make[IO]` with a fake token endpoint that answers 400 to `bad-code` and 200 to `code-2`. The test runs a poll, hands `bad-code`, and asserts that the poll fails. It then starts a second poll in a fiber, checks that the fiber hasn't finished, hands `code-2`, and asserts that playback comes back. ScalaCheck properties over the order of attempt and hand cover SignInCodes: a hand before any attempt gives false, one code per attempt, and a spent attempt is never reused.
3. **3. Start from the configured refresh token.** Adds `signIn`, and the `None` branch of `getOrRefresh` calls it. A valid token starts the lights without a sign-in URL. invalid_grant logs a token-free WARN and falls back to the browser. 429, invalid_client and 5xx are retried on the next poll. The README marks SPOTIFY_REFRESH_TOKEN as optional, and how-it-works §1 describes the swap.
   - With a valid token, the first poll sends exactly one refresh_token grant, plays, and logs no 'Please visit' (AC 2)
   - With a 400 invalid_grant answer, there is one WARN that the configured refresh token was rejected, and no log event contains the token value. Then 'Please visit' is logged, and handing a code via /callback leads to a successful poll (AC 3)
   - With the token unset or empty, no refresh_token grant is sent and 'Please visit' is logged. Handing a code completes sign-in (AC 1)
   - For a 429, a 401 invalid_client or a 503 answer, the poll fails without logging a sign-in URL, and the next poll tries the swap again
   - After a browser sign-in, renew sends the refresh token from the code exchange, not the configured one
   - DocsSpec: the README's SPOTIFY_REFRESH_TOKEN row says it's optional
   - Tests: In SpotifyClientSpec, a table of configured token × token-endpoint answer (200, 400 invalid_grant, 429, 401 invalid_client, 503, none), run against a recording fake built with Client.fromHttpApp. Logs come from `Tls.capturedLogs`/`Tls.logged`, filtered on the logger `client.SpotifyClient` and a client_id unique to each test. SyncEngineRunSpec, under TestControl, shows the lights following playback in the valid, rejected and absent cases. Existing sign-in tests switch to `SpotifyCredentials("id", "secret")`. The TLS tests that start with no session expect 'Refresh Token Exchange'. A new DocsSpec reads README.md from the project root.
4. **4. Drop the sp_dc cookie and its docs.** `initialAuth` no longer requests /authorize, and nothing reads SPOTIFY_SP_DC. README, .env.example and how-it-works no longer mention it. build.sbt forks the test JVM with `Test / fork := true` and `Test / envVars += "SPOTIFY_SP_DC" -> "sp-dc-sentinel"`, so the whole suite runs with the variable set.
   - A guard test asserts that SPOTIFY_SP_DC equals the sentinel in the test JVM
   - On every sign-in path (valid, rejected, absent token), no recorded request has a Cookie header or goes to /authorize, and the sentinel appears in no request and no log line (AC 4)
   - The full suite, sign-in and playback included, passes with SPOTIFY_SP_DC set (AC 5)
   - DocsSpec: README.md, .env.example and docs/how-it-works.md contain no 'sp_dc' (case-insensitive) (AC 6)
   - Tests: SpotifyClientSpec and SpotifyTlsSpec complete sign-in through `callbackRoutes` instead of a fake /authorize. A table over the three paths checks every recorded request's headers, URI and body, and every captured log message, for the sentinel. The guard test fails if the fork or envVars setting is removed. DocsSpec is extended to cover sp_dc. Every new assertion fails on master, where the cookie is sent and the docs mention it.

## Alternatives

- Sign in eagerly in `SpotifyClient.resource`: Hue setup would wait on browser approval, and a 5xx at startup would stop the app instead of being retried on the next poll.
- `refreshToken: Option[String]`: a more precise type, but every `SpotifyCredentials(...)` call site in four suites would change, and an empty value from `.env` still needs normalising. The default `""` plus `configuredRefreshToken` gives the same behaviour with less change.
- Replace the `Deferred` parameter outright instead of keeping overloads: that means rewriting the constructor call in ten places across four suites, purely to change types, inside the same PR.