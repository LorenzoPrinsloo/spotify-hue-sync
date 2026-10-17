package client

import cats.effect.testkit.TestControl
import cats.effect.{Deferred, IO, Ref}
import ch.qos.logback.classic.Level
import cats.syntax.all.*
import domain.spotify.{SpotifyCredentials, SpotifySession}
import domain.spotify.types.TrackId
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.typelevel.ci.CIStringSyntax
import testkit.Tls

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

class SpotifyClientSpec extends CatsEffectSuite {

  private def spotify(response: IO[Response[IO]]): IO[SpotifyClient[IO]] =
    for {
      session <- Ref.of[IO, Option[SpotifySession]](Some(SpotifySession("token", "refresh")))
      code <- Deferred[IO, String]
      app = HttpRoutes.of[IO] { case GET -> Root / "v1" / "audio-features" / "track-1" => response }.orNotFound
    } yield new SpotifyClient[IO](Client.fromHttpApp(app), SpotifyCredentials("id", "secret", Some("refresh")), session, code)

  test("reads the tempo from Spotify's audio features") {
    spotify(Ok("""{ "id": "track-1", "tempo": 123.5, "energy": 0.8 }"""))
      .flatMap(_.getTempo(TrackId("track-1")))
      .assertEquals(Some(123.5))
  }

  test("no tempo when Spotify refuses the request") {
    spotify(Forbidden("""{ "error": { "status": 403 } }""")).flatMap(_.getTempo(TrackId("track-1"))).assertEquals(None)
  }

  test("no tempo when the track has none") {
    spotify(Ok("""{ "id": "track-1", "tempo": 0 }""")).flatMap(_.getTempo(TrackId("track-1"))).assertEquals(None)
  }

  private val playing =
    """{ "progress_ms": 0, "is_playing": true, "item": { "id": "track-1", "album": { "images": [ { "url": "cover" } ] } } }"""

  private val rejected: IO[Response[IO]] = IO.pure(Response[IO](Status.Unauthorized))

  private case class Expiring(client: SpotifyClient[IO], session: Ref[IO, Option[SpotifySession]], renewals: Ref[IO, List[String]])

  /**
   * A Spotify that only accepts the access token "fresh", starting from a session whose access token has expired.
   * The token endpoint answers with `renewal` and records the refresh tokens it was sent.
   */
  private def expiring(renewal: IO[Response[IO]]): IO[Expiring] =
    for {
      session <- Ref.of[IO, Option[SpotifySession]](Some(SpotifySession("expired", "refresh-1")))
      renewals <- Ref.of[IO, List[String]](Nil)
      code <- Deferred[IO, String]
      fresh = Authorization(Credentials.Token(AuthScheme.Bearer, "fresh"))
      app = HttpRoutes.of[IO] {
        case req @ POST -> Root / "api" / "token" =>
          req.as[UrlForm].flatMap(form => renewals.update(_ :+ form.getFirstOrElse("refresh_token", ""))) *> renewal
        case req @ GET -> Root / "v1" / "me" / "player" =>
          if (req.headers.get[Authorization].contains(fresh)) Ok(playing) else rejected
        case req @ GET -> Root / "v1" / "audio-features" / "track-1" =>
          if (req.headers.get[Authorization].contains(fresh)) Ok("""{ "tempo": 123.5 }""") else rejected
      }.orNotFound
    } yield Expiring(new SpotifyClient[IO](Client.fromHttpApp(app), SpotifyCredentials("id", "secret", Some("refresh-0")), session, code), session,
      renewals)

  private def renewedTo(access: String, refresh: Option[String]): IO[Response[IO]] =
    Ok(
      s"""{ "access_token": "$access", "token_type": "Bearer", "expires_in": 3600, "scope": "user-read-playback-state"${refresh.fold("")(r => s""", "refresh_token": "$r"""")} }"""
    )

  private def renewed(refresh: Option[String]): IO[Response[IO]] = renewedTo("fresh", refresh)

  test("when the access token has expired, renews it with the refresh token, once, and carries on") {
    for {
      spotify <- expiring(renewed(None))
      polls <- spotify.client.getPlaybackState.replicateA(3)
      _ = assert(polls.forall(_.exists(_.isPlaying)), polls)
      _ <- spotify.renewals.get.assertEquals(List("refresh-1"))
    } yield ()
  }

  test("tempo lookups renew an expired access token too") {
    expiring(renewed(None)).flatMap(_.client.getTempo(TrackId("track-1"))).assertEquals(Some(123.5))
  }

  test("keeps the refresh token Spotify sends back when renewing, or the old one if it sends none") {
    List(None -> "refresh-1", Some("refresh-2") -> "refresh-2").traverse_ { (returned, kept) =>
      for {
        spotify <- expiring(renewed(returned))
        _ <- spotify.client.getPlaybackState
        _ <- spotify.session.get.assertEquals(Some(SpotifySession("fresh", kept)))
      } yield ()
    }
  }

  test("fails, instead of reporting that nothing is playing, when the access token can't be renewed") {
    expiring(BadRequest("""{ "error": "invalid_grant" }""")).flatMap(_.client.getPlaybackState).intercept[RuntimeException]
  }

  private def handCode(code: Deferred[IO, String], value: String): IO[Response[IO]] =
    SpotifyClient.callbackRoutes[IO](SignInCodes.once(code)).orNotFound.run(Request(Method.GET, uri"/callback".withQueryParam("code", value)))

  test("signs in on first use: swaps the code from the callback for tokens, then uses them") {
    for {
      session <- Ref.of[IO, Option[SpotifySession]](None)
      code <- Deferred[IO, String]
      exchanged <- Ref.of[IO, Option[UrlForm]](None)
      signedIn = Authorization(Credentials.Token(AuthScheme.Bearer, "signed-in"))
      app = HttpRoutes.of[IO] {
        case req @ POST -> Root / "api" / "token" =>
          req.as[UrlForm].flatMap(form => exchanged.set(Some(form))) *> renewedTo("signed-in", Some("refresh-9"))
        case req @ GET -> Root / "v1" / "me" / "player" =>
          if (req.headers.get[Authorization].contains(signedIn)) Ok(playing) else rejected
      }.orNotFound
      _ <- handCode(code, "code-1")
      state <- new SpotifyClient[IO](Client.fromHttpApp(app), SpotifyCredentials("id", "secret"), session, code).getPlaybackState
      form <- exchanged.get
      stored <- session.get
    } yield {
      assert(state.exists(_.isPlaying), state.toString)
      assertEquals(form.flatMap(_.getFirst("grant_type")), Some("authorization_code"))
      assertEquals(form.flatMap(_.getFirst("code")), Some("code-1"))
      assertEquals(stored, Some(SpotifySession("signed-in", "refresh-9")))
    }
  }

  test("sign-in fails, rather than carrying on without a token, when Spotify won't swap the code") {
    for {
      session <- Ref.of[IO, Option[SpotifySession]](None)
      code <- Deferred[IO, String]
      app = HttpRoutes.of[IO] {
        case POST -> Root / "api" / "token" => BadRequest("""{ "error": "invalid_grant" }""")
      }.orNotFound
      _ <- handCode(code, "code-1")
      _ <- new SpotifyClient[IO](Client.fromHttpApp(app), SpotifyCredentials("id", "secret"), session, code)
        .getPlaybackState.intercept[RuntimeException]
      stored <- session.get
    } yield assertEquals(stored, None)
  }

  test("the callback hands the sign-in code over, and nothing else counts as a callback") {
    for {
      code <- Deferred[IO, String]
      routes = SpotifyClient.callbackRoutes[IO](SignInCodes.once(code)).orNotFound
      answered <- routes.run(Request(Method.GET, uri"/callback?code=abc&state=xyz"))
      handed <- code.get
      without <- routes.run(Request(Method.GET, uri"/callback"))
    } yield {
      assertEquals((answered.status, handed), (Status.Ok, "abc"))
      assertEquals(without.status, Status.NotFound)
    }
  }

  private val signedInBearer = Authorization(Credentials.Token(AuthScheme.Bearer, "signed-in"))

  private def playerFor(req: Request[IO]): IO[Response[IO]] =
    if (req.headers.get[Authorization].contains(signedInBearer)) Ok(playing) else rejected

  private case class Retry(
    failed: Either[Throwable, ?],
    afterFailure: Option[SpotifySession],
    waiting: Boolean,
    signedIn: Boolean,
    exchanged: List[String]
  )

  private def retryAfterFailedExchange: IO[Retry] = {
    val flow = for {
      codes <- SignInCodes.make[IO]
      session <- Ref.of[IO, Option[SpotifySession]](None)
      exchanged <- Ref.of[IO, List[String]](Nil)
      fake = HttpRoutes.of[IO] {
        case req @ POST -> Root / "api" / "token" =>
          req.as[UrlForm].flatMap { form =>
            val code = form.getFirstOrElse("code", "")
            exchanged.update(_ :+ code) *>
              (if (code == "bad-code") BadRequest("""{ "error": "invalid_grant" }""") else renewedTo("signed-in", Some("refresh-9")))
          }
        case req @ GET -> Root / "v1" / "me" / "player" => playerFor(req)
      }.orNotFound
      routes = SpotifyClient.callbackRoutes[IO](codes).orNotFound
      spotify = new SpotifyClient[IO](Client.fromHttpApp(fake), SpotifyCredentials(retryClientId, "secret"), session, codes)
      poll1 <- spotify.getPlaybackState.start
      _ <- IO.sleep(1.second)
      _ <- routes.run(Request(Method.GET, uri"/callback?code=bad-code"))
      failed <- poll1.joinWithNever.attempt
      afterFailure <- session.get
      finished <- Ref.of[IO, Boolean](false)
      poll2 <- (spotify.getPlaybackState <* finished.set(true)).start
      _ <- IO.sleep(1.second)
      waiting <- finished.get.map(!_)
      _ <- routes.run(Request(Method.GET, uri"/callback?code=code-2"))
      state <- poll2.joinWithNever
      seenAfter <- exchanged.get
    } yield Retry(failed, afterFailure, waiting, state.exists(_.isPlaying), seenAfter)
    TestControl.executeEmbed(flow)
  }

  private val retryClientId = "retry-sign-in"

  test("after a failed code exchange the next poll waits for a fresh code instead of reusing the failed one") {
    retryAfterFailedExchange.map { r =>
      assert(r.failed.left.exists { case _: RuntimeException => true; case _ => false }, r.failed.toString)
      assertEquals(r.afterFailure, None)
      assert(r.waiting)
    }
  }

  test("after a failed code exchange the next poll logs a new URL and signs in with the fresh code") {
    (Tls.capturedLogs, Tls.loggingAt("client.SpotifyClient", Level.INFO)).tupled.use { (appender, _) =>
      retryAfterFailedExchange.map { r =>
        assert(r.signedIn)
        assertEquals(r.exchanged, List("bad-code", "code-2"))
        val visits = Tls.logged(appender).filter { e =>
          e.getLoggerName == "client.SpotifyClient" && e.getFormattedMessage.contains("Please visit") && e.getFormattedMessage.contains(retryClientId)
        }
        assertEquals(visits.size, 2)
      }
    }
  }

  test("a callback with no sign-in waiting answers the usual page and the code is dropped") {
    val flow = for {
      codes <- SignInCodes.make[IO]
      routes = SpotifyClient.callbackRoutes[IO](codes).orNotFound
      answered <- routes.run(Request(Method.GET, uri"/callback?code=dropped"))
      body <- answered.as[String]
      later <- codes.attempt.flatMap(_.timeout(1.second)).attempt
    } yield {
      assertEquals(answered.status, Status.Ok)
      assert(body.contains("Success! Return to your terminal."), body)
      assert(later.left.exists { case _: TimeoutException => true; case _ => false }, later.toString)
    }
    TestControl.executeEmbed(flow)
  }

  private case class TokenCall(grant: String, refreshToken: String, code: String)

  private case class Sent(method: Method, uri: Uri, headers: Headers, body: String)

  private case class Rig(
    spotify: SpotifyClient[IO],
    session: Ref[IO, Option[SpotifySession]],
    routes: HttpApp[IO],
    tokenCalls: IO[List[TokenCall]],
    sent: IO[List[Sent]]
  ) {
    def handCode: IO[Response[IO]] = routes.run(Request(Method.GET, uri"/callback?code=browser-code"))
  }

  private def rig(clientId: String, configured: Option[String], answer: IO[Response[IO]]): IO[Rig] =
    for {
      codes <- SignInCodes.make[IO]
      session <- Ref.of[IO, Option[SpotifySession]](None)
      calls <- Ref.of[IO, List[TokenCall]](Nil)
      sent <- Ref.of[IO, List[Sent]](Nil)
      fake = HttpRoutes.of[IO] {
        case req @ POST -> Root / "api" / "token" =>
          req.as[UrlForm].flatMap { form =>
            val call = TokenCall(form.getFirstOrElse("grant_type", ""), form.getFirstOrElse("refresh_token", ""), form.getFirstOrElse("code", ""))
            calls.update(_ :+ call) *>
              (call match {
                case TokenCall("refresh_token", "refresh-9", _) => renewedTo("renewed", None)
                case TokenCall("refresh_token", _, _) => answer
                case _ => renewedTo("signed-in", Some("refresh-9"))
              })
          }
        case req @ GET -> Root / "v1" / "me" / "player" => playerFor(req)
      }.orNotFound
      recording = HttpApp[IO] { req =>
        req.bodyText.compile.string.flatMap { body =>
          sent.update(Sent(req.method, req.uri, req.headers, body) :: _) *> fake.run(req.withEntity(body).withHeaders(req.headers))
        }
      }
    } yield Rig(
      new SpotifyClient[IO](Client.fromHttpApp(recording), SpotifyCredentials(clientId, "secret", configured), session, codes),
      session,
      SpotifyClient.callbackRoutes[IO](codes).orNotFound,
      calls.get,
      sent.get
    )

  private def browserSignIn(r: Rig): IO[Option[domain.spotify.PlaybackState]] =
    for {
      poll <- r.spotify.getPlaybackState.start
      _ <- IO.sleep(1.second)
      _ <- r.handCode
      state <- poll.joinWithNever
    } yield state

  private def visits(appender: ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent], clientId: String): Int =
    Tls.logged(appender).count { e =>
      e.getLoggerName == "client.SpotifyClient" && e.getFormattedMessage.startsWith("Please visit") && e.getFormattedMessage.contains(clientId)
    }

  private def infoLines(
    appender: ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent],
    clientId: String,
    message: String
  ): Int =
    Tls.logged(appender).count { e =>
      e.getLoggerName == "client.SpotifyClient" && e.getLevel == Level.INFO && e.getFormattedMessage == message &&
      e.getMDCPropertyMap.get("client_id") == clientId
    }

  private def warnings(appender: ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent], clientId: String) =
    Tls.logged(appender).filter { e =>
      e.getLoggerName == "client.SpotifyClient" && e.getLevel == Level.WARN && e.getMDCPropertyMap.get("client_id") == clientId
    }

  private val loggedAtInfo = (Tls.capturedLogs, Tls.loggingAt("client.SpotifyClient", Level.INFO)).tupled

  test("a valid configured refresh token signs in with one swap and never opens the browser") {
    val id = "configured-valid"
    val token = "token-valid"
    loggedAtInfo.use { (appender, _) =>
      val flow = for {
        r <- rig(id, Some(token), renewedTo("signed-in", Some("refresh-9")))
        state <- r.spotify.getPlaybackState
        calls <- r.tokenCalls
        sent <- r.sent
      } yield (state, calls, sent)
      TestControl.executeEmbed(flow).map { (state, calls, sent) =>
        assert(state.exists(_.isPlaying), state.toString)
        assertEquals(calls, List(TokenCall("refresh_token", token, "")))
        assert(!sent.exists(_.uri.path.renderString == "/authorize"), sent.toString)
        assertEquals(visits(appender, id), 0)
        assertEquals(infoLines(appender, id, "Signed in to Spotify with the configured refresh token"), 1)
        assertEquals(infoLines(appender, id, "Signed in to Spotify through the browser"), 0)
      }
    }
  }

  test("a rejected configured refresh token warns once without the token, then signs in through the browser") {
    val id = "configured-rejected"
    val token = "token-rejected"
    loggedAtInfo.use { (appender, _) =>
      val flow = for {
        r <- rig(id, Some(token), BadRequest("""{"error":"invalid_grant"}"""))
        state <- browserSignIn(r)
        calls <- r.tokenCalls
      } yield (state, calls)
      TestControl.executeEmbed(flow).map { (state, calls) =>
        assert(state.exists(_.isPlaying), state.toString)
        assertEquals(calls.map(_.grant), List("refresh_token", "authorization_code"))
        assertEquals(visits(appender, id), 1)
        val warned = warnings(appender, id).map(_.getFormattedMessage)
        assertEquals(warned.size, 1)
        assert(warned.forall(_.startsWith("Spotify rejected the configured refresh token (status: 400")), warned.toString)
        val texts = Tls.logged(appender).flatMap { e =>
          e.getFormattedMessage :: e.getMDCPropertyMap.values.toArray.toList.map(_.toString) ::: Option(e.getThrowableProxy).map(_.getMessage).toList
        }
        assert(!texts.exists(_.contains(token)), texts.toString)
      }
    }
  }

  List(
    ("answers 429", "configured-429", TooManyRequests("""{"error":"slow down"}"""), Status.TooManyRequests),
    ("answers invalid_client", "configured-invalid-client", IO.pure(Response[IO](Status.Unauthorized).withEntity("""{"error":"invalid_client"}""")),
      Status.Unauthorized),
    ("answers 503", "configured-503", ServiceUnavailable("down"), Status.ServiceUnavailable)
  ).foreach { (what, id, answer, status) =>
    test(s"when Spotify $what to the configured refresh token the poll fails, no browser opens, and the next poll swaps again") {
      val token = s"token-$id"
      loggedAtInfo.use { (appender, _) =>
        val flow = for {
          r <- rig(id, Some(token), answer)
          first <- r.spotify.getPlaybackState.attempt
          stored <- r.session.get
          second <- r.spotify.getPlaybackState.attempt
          calls <- r.tokenCalls
        } yield (first, second, stored, calls)
        TestControl.executeEmbed(flow).map { (first, second, stored, calls) =>
          List(first, second).foreach { outcome =>
            assert(
              outcome.left.exists {
                case SpotifyTokenError.Failed("Refresh Token Exchange", s, _) => s == status
                case _ => false
              },
              outcome.toString
            )
          }
          assertEquals(stored, None)
          assertEquals(calls, List.fill(2)(TokenCall("refresh_token", token, "")))
          assertEquals(visits(appender, id), 0)
        }
      }
    }
  }

  test("with no configured refresh token it goes straight to the browser sign-in") {
    val id = "unconfigured-none"
    loggedAtInfo.use { (appender, _) =>
      val flow = for {
        r <- rig(id, None, renewedTo("unused", None))
        state <- browserSignIn(r)
        calls <- r.tokenCalls
      } yield (state, calls)
      TestControl.executeEmbed(flow).map { (state, calls) =>
        assert(state.exists(_.isPlaying), state.toString)
        assertEquals(calls.map(_.grant), List("authorization_code"))
        assertEquals(visits(appender, id), 1)
        assertEquals(infoLines(appender, id, "Signed in to Spotify through the browser"), 1)
        assertEquals(infoLines(appender, id, "Signed in to Spotify with the configured refresh token"), 0)
      }
    }
  }

  List(
    ("a valid configured token", "sp-dc-valid", Some("token-sp-dc-valid"), renewedTo("signed-in", Some("refresh-9")), false),
    ("a rejected configured token", "sp-dc-rejected", Some("token-sp-dc-rejected"), BadRequest("""{"error":"invalid_grant"}"""), true),
    ("no configured token", "sp-dc-absent", None, renewedTo("unused", None), true)
  ).foreach { (what, id, configured, answer, viaBrowser) =>
    test(s"with $what the sp_dc cookie is never sent and never logged") {
      val sentinel = sys.env.getOrElse("SPOTIFY_SP_DC", fail("SPOTIFY_SP_DC is not set for the test JVM"))
      loggedAtInfo.use { (appender, _) =>
        val flow = for {
          r <- rig(id, configured, answer)
          state <- if (viaBrowser) browserSignIn(r) else r.spotify.getPlaybackState
          sent <- r.sent
        } yield (state, sent)
        TestControl.executeEmbed(flow).map { (state, sent) =>
          assert(state.exists(_.isPlaying), state.toString)
          assert(sent.forall(_.headers.get(ci"Cookie").isEmpty), sent.toString)
          assert(!sent.exists(_.uri.path.renderString == "/authorize"), sent.toString)
          val requestTexts = sent.flatMap(s => List(s.headers.headers.map(_.toString).mkString("\n"), s.uri.renderString, s.body))
          assert(!requestTexts.exists(_.contains(sentinel)), "the sp_dc sentinel reached a request")
          val logTexts = Tls.logged(appender).flatMap { e =>
            e.getFormattedMessage :: e.getMDCPropertyMap.values.toArray.toList.map(_.toString) :::
              Option(e.getThrowableProxy).map(_.getMessage).toList
          }
          assert(!logTexts.exists(_.contains(sentinel)), "the sp_dc sentinel reached a log line")
        }
      }
    }
  }

  test("after a browser sign-in replaces a rejected token, renewing uses the new refresh token") {
    val token = "token-ac5"
    val flow = for {
      r <- rig("configured-ac5", Some(token), BadRequest("""{"error":"invalid_grant"}"""))
      _ <- browserSignIn(r)
      renewed <- r.spotify.renew
      calls <- r.tokenCalls
    } yield (renewed, calls)
    TestControl.executeEmbed(flow).map { (renewed, calls) =>
      assertEquals(renewed, "renewed")
      assertEquals(
        calls,
        List(TokenCall("refresh_token", token, ""), TokenCall("authorization_code", "", "browser-code"), TokenCall("refresh_token", "refresh-9", ""))
      )
    }
  }
}
