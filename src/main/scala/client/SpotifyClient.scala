package client

import cats.effect.*
import cats.effect.implicits.genSpawnOps
import cats.syntax.all.*
import domain.spotify.*
import domain.spotify.types.TrackId
import org.http4s.{client, *}
import org.http4s.client.Client
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Authorization
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.typelevel.ci.CIStringSyntax
import org.typelevel.log4cats.slf4j.Slf4jLogger
import com.comcast.ip4s.{ipv4, port}

import scala.concurrent.duration.*
import org.http4s.dsl.io.*
import fs2.io.net.Network
import fs2.text
import io.circe.Json
import org.http4s.*
import org.http4s.dsl.Http4sDsl

import scala.util.Random

/** A Spotify call that failed because its certificate couldn't be trusted. */
final case class SpotifyTlsFailure(call: String, cause: Throwable)
    extends RuntimeException(s"Spotify $call failed on its certificate: $cause", cause)

class SpotifyClient[F[_]: Async: Network](
  client: Client[F],
  creds: SpotifyCredentials,
  session: Ref[F, Option[SpotifySession]],
  codes: SignInCodes[F]
) extends Http4sClientDsl[F]:

  /** Signs in through the browser at most once, with the code `authCode` is completed with. */
  def this(client: Client[F], creds: SpotifyCredentials, session: Ref[F, Option[SpotifySession]], authCode: Deferred[F, String]) =
    this(client, creds, session, SignInCodes.once[F](authCode))

  private val authUri = uri"https://accounts.spotify.com/api/token"
  private val apiUri = uri"https://api.spotify.com/v1"

  private val logger = Slf4jLogger.getLogger[F]
  private val random: Random = new Random()

  /** Names `call` in a failure caused by TLS, so the log says which Spotify call couldn't trust the certificate. */
  private def naming[A](call: String)(fa: F[A]): F[A] =
    fa.adaptError { case err if causedByTls(err) => SpotifyTlsFailure(call, err) }

  /** Bounded so a cyclic cause chain can't loop forever. */
  private val MaxCauseDepth = 20

  private def causedByTls(err: Throwable): Boolean =
    Iterator
      .unfold[Throwable, Option[Throwable]](Some(err))(_.map(t => (t, Option(t.getCause))))
      .take(MaxCauseDepth)
      .exists {
        case _: javax.net.ssl.SSLException => true
        case _ => false
      }

  def initialAuth: F[String] =
    val redirectUri = "http://127.0.0.1:7070/callback"
    val scopes = List("user-read-playback-state", "user-read-currently-playing").mkString("%20")
    val state = s"random_${random.nextLong()}"
    val authUrl =
      s"https://accounts.spotify.com/authorize?client_id=${creds.clientId}&response_type=code&redirect_uri=$redirectUri&scope=$scopes&state=$state"

    for
      waitForCode <- codes.attempt
      _ <- logger.info(s"Please visit: $authUrl")
      // With a valid session cookie this lands on the callback with the code; otherwise it's Spotify's login page, and
      // the code comes when the authorize URL is opened in a browser. Either way the page itself isn't needed.
      _ <- naming("Authorize")(client.run(Request[F](Method.GET, Uri.unsafeFromString(authUrl)).putHeaders(Header.Raw(ci"Cookie",
              s"sp_dc=${sys.env.getOrElse("SPOTIFY_SP_DC", "")}")))
          .use(_.body.compile.drain))
      code <- waitForCode
      authHeader = Authorization(BasicCredentials(creds.clientId, creds.clientSecret))
      form = UrlForm(
        "grant_type" -> "authorization_code",
        "code" -> code,
        "redirect_uri" -> redirectUri
      )
      req = Request[F](Method.POST, authUri)
        .withEntity(form)
        .putHeaders(authHeader)
      resp <- naming("Code Exchange")(client.expectOr[SpotifyToken](req) { errorRes =>
        errorRes.as[Json].flatMap { body =>
          logger.error(s"Spotify Exchange Failed. Body: $body") *>
            Async[F].raiseError(new RuntimeException(s"Spotify Error ${errorRes.status}: $body"))
        }
      })
      refresh <- resp.refresh_token.fold(Async[F].raiseError[String](new RuntimeException("Spotify sent no refresh token with the code exchange")))(
        Async[F].pure
      )
      _ <- session.set(Some(SpotifySession(resp.access_token, refresh)))
      _ <- logger.info("Signed in to Spotify through the browser")
    yield resp.access_token

  /**
   * Starts a session from the configured refresh token, falling back to the browser sign-in when there is none or
   * Spotify answers that it's no good (invalid_grant).
   */
  def signIn: F[String] =
    creds.configuredRefreshToken.fold(initialAuth) { token =>
      refreshWith("Refresh Token Exchange", token)
        .flatTap(_ => logger.info("Signed in to Spotify with the configured refresh token"))
        .recoverWith { case SpotifyTokenError.Rejected(_, status, _) =>
          logger.warn(Map("client_id" -> creds.clientId))(
            s"Spotify rejected the configured refresh token (status: $status); signing in through the browser instead"
          ) *> initialAuth
        }
    }

  /**
   * Helper to always get a valid token.
   */
  def getOrRefresh: F[String] =
    session.get.flatMap:
      case Some(s) => Async[F].pure(s.accessToken)
      case None => signIn

  /**
   * Swaps the refresh token for a new access token, keeping the refresh token Spotify sends back, if any.
   * Spotify's access tokens expire after an hour.
   */
  def renew: F[String] =
    for
      token <- session.get.flatMap {
        case Some(s) => refreshWith("Renew", s.refreshToken)
        case None => creds.configuredRefreshToken.fold(initialAuth)(refreshWith("Renew", _))
      }
      _ <- logger.info("Renewed the Spotify access token")
    yield token

  /**
   * Swaps `refresh` for a new access token and stores the session, keeping `refresh` if Spotify sends no new one.
   * A failure is a `SpotifyTokenError` named `call`.
   */
  private def refreshWith(call: String, refresh: String): F[String] =
    val req = Request[F](Method.POST, authUri)
      .withEntity(UrlForm("grant_type" -> "refresh_token", "refresh_token" -> refresh))
      .putHeaders(Authorization(BasicCredentials(creds.clientId, creds.clientSecret)))
    for
      resp <- naming(call)(client.expectOr[SpotifyToken](req) { errorRes =>
        errorRes.bodyText.compile.string.flatMap { body =>
          Async[F].raiseError(SpotifyTokenError.of(call, errorRes.status, body))
        }
      })
      _ <- session.set(Some(SpotifySession(resp.access_token, resp.refresh_token.getOrElse(refresh))))
    yield resp.access_token

  /**
   * Sends `request` with the access token. If Spotify rejects the token, renews it and sends the request once more,
   * so `read` only sees a 401 if the renewed token is rejected too.
   */
  private def authorised[A](call: String, request: Request[F])(read: Response[F] => F[A]): F[A] =
    def send(token: String): Resource[F, Response[F]] =
      client.run(request.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token))))

    getOrRefresh
      .flatMap(token =>
        naming(call)(send(token).use(resp => if resp.status == Status.Unauthorized then Async[F].pure(None) else read(resp).map(Some(_))))
      )
      .flatMap {
        case Some(result) => Async[F].pure(result)
        case None => renew.flatMap(token => naming(call)(send(token).use(read)))
      }

  /**
   * Port of fetch-spotify-playback-state.ts
   */
  def getPlaybackState: F[Option[PlaybackState]] =
    authorised("Playback Poll", GET(apiUri / "me" / "player")) { resp =>
      if resp.status == Status.NoContent then Async[F].pure(None)
      else resp.as[PlaybackState].map(Some(_))
    }

  /**
   * The track's tempo in BPM from Spotify's audio features, or None if Spotify won't provide it
   * (the endpoint is restricted for newer Spotify apps).
   */
  def getTempo(id: TrackId): F[Option[Double]] =
    authorised("Tempo Lookup", GET(apiUri / "audio-features" / id.value)) { resp =>
      if resp.status.isSuccess then resp.as[Json].map(_.hcursor.downField("tempo").as[Double].toOption.filter(_ > 0))
      else Async[F].pure(None)
    }

object SpotifyClient:

  /** Where Spotify sends the browser back after sign-in: hands the code it carries to the sign-in waiting for it. */
  def callbackRoutes[F[_]: Async](codes: SignInCodes[F]): HttpRoutes[F] = {
    val dsl = new org.http4s.dsl.Http4sDsl[F] {}
    import dsl.*
    HttpRoutes.of[F] {
      case GET -> Root / "callback" :? CodeMatcher(code) =>
        codes.hand(code) *> Ok("Success! Return to your terminal.")
    }
  }

  def serverResource[F[_]: Async: Network](codes: SignInCodes[F]): Resource[F, org.http4s.server.Server] =
    EmberServerBuilder.default[F].withHost(ipv4"127.0.0.1").withPort(port"7070").withHttpApp(callbackRoutes[F](codes).orNotFound).build

  def resource[F[_]: Async: Network](client: Client[F], creds: SpotifyCredentials): Resource[F, SpotifyClient[F]] =
    for
      session <- Resource.eval(Ref.of[F, Option[SpotifySession]](None))
      codes <- Resource.eval(SignInCodes.make[F])
      _ <- serverResource(codes)
    yield new SpotifyClient[F](client, creds, session, codes)

  object CodeMatcher extends QueryParamDecoderMatcher[String]("code")
