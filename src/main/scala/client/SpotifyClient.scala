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
import org.http4s.dsl.io.{GET, Ok, POST, QueryParamDecoderMatcher, Root}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.typelevel.ci.CIStringSyntax
import org.typelevel.log4cats.slf4j.Slf4jLogger
import com.comcast.ip4s.{ipv4, port}

import scala.concurrent.duration.*
import org.http4s.dsl.io.*
import cats.implicits.*
import fs2.io.net.Network
import fs2.text
import io.circe.Json
import org.http4s.*
import org.http4s.dsl.Http4sDsl

import scala.util.Random

class SpotifyClient[F[_]: Async: Network](
  client: Client[F],
  creds: SpotifyCredentials,
  session: Ref[F, Option[SpotifySession]],
  authCodeDeferred: Deferred[F, String]
) extends Http4sClientDsl[F]:

  private val authUri = uri"https://accounts.spotify.com/api/token"
  private val apiUri = uri"https://api.spotify.com/v1"

  private val logger = Slf4jLogger.getLogger[F]
  private val random: Random = new Random()

  def initialAuth: F[String] =
    val redirectUri = "http://127.0.0.1:7070/callback"
    val scopes = List("user-read-playback-state", "user-read-currently-playing").mkString("%20")
    val state = s"random_${random.nextLong()}"
    val authUrl = s"https://accounts.spotify.com/authorize?client_id=${creds.clientId}&response_type=code&redirect_uri=$redirectUri&scope=$scopes&state=$state"

    for
      _ <- logger.info(s"Please visit: $authUrl")
      status <- client.run(Request[F](Method.GET, Uri.unsafeFromString(authUrl)).putHeaders(Header.Raw(ci"Cookie", s"sp_dc=${sys.env.getOrElse("SPOTIFY_SP_DC", "")}")))
        .use { res =>
          res.as[String]
        }
     // r <- client.run(req)
      code <- authCodeDeferred.get // This blocks (semantically) until the route is hit
      authHeader = Authorization(BasicCredentials(creds.clientId, creds.clientSecret))
      form = UrlForm(
        "grant_type" -> "authorization_code",
        "code" -> code,
        "redirect_uri" -> redirectUri
      )
      req = Request[F](Method.POST, authUri)
        .withEntity(form)
        .putHeaders(authHeader)
      resp <- client.expectOr[SpotifyToken](req) { errorRes =>
        errorRes.as[Json].flatMap { body =>
          logger.error(s"Spotify Exchange Failed. Body: $body") *>
            Async[F].raiseError(new RuntimeException(s"Spotify Error ${errorRes.status}: $body"))
        }
      }
      _ <- session.set(Some(SpotifySession(resp.access_token, resp.refresh_token.getOrElse(creds.refreshToken))))
    yield resp.access_token


  /**
   * Helper to always get a valid token.
   */
  def getOrRefresh: F[String] =
    session.get.flatMap:
      case Some(s) => Async[F].pure(s.accessToken)
      case None    => initialAuth

  /**
   * Swaps the refresh token for a new access token, keeping the refresh token Spotify sends back, if any.
   * Spotify's access tokens expire after an hour.
   */
  def renew: F[String] =
    for
      refresh <- session.get.map(_.fold(creds.refreshToken)(_.refreshToken))
      req = Request[F](Method.POST, authUri)
        .withEntity(UrlForm("grant_type" -> "refresh_token", "refresh_token" -> refresh))
        .putHeaders(Authorization(BasicCredentials(creds.clientId, creds.clientSecret)))
      resp <- client.expectOr[SpotifyToken](req) { errorRes =>
        errorRes.bodyText.compile.string.flatMap { body =>
          Async[F].raiseError(new RuntimeException(s"Couldn't renew the Spotify access token, ${errorRes.status}: $body"))
        }
      }
      _ <- session.set(Some(SpotifySession(resp.access_token, resp.refresh_token.getOrElse(refresh))))
      _ <- logger.info("Renewed the Spotify access token")
    yield resp.access_token

  /**
   * Sends `request` with the access token. If Spotify rejects the token, renews it and sends the request once more,
   * so `read` only sees a 401 if the renewed token is rejected too.
   */
  private def authorised[A](request: Request[F])(read: Response[F] => F[A]): F[A] =
    def send(token: String): Resource[F, Response[F]] =
      client.run(request.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token))))

    getOrRefresh
      .flatMap(token => send(token).use(resp => if resp.status == Status.Unauthorized then Async[F].pure(None) else read(resp).map(Some(_))))
      .flatMap {
        case Some(result) => Async[F].pure(result)
        case None         => renew.flatMap(send(_).use(read))
      }

  /**
   * Port of fetch-spotify-playback-state.ts
   */
  def getPlaybackState: F[Option[PlaybackState]] =
    authorised(GET(apiUri / "me" / "player")) { resp =>
      if resp.status == Status.NoContent then Async[F].pure(None)
      else resp.as[PlaybackState].map(Some(_))
    }

  /**
   * The track's tempo in BPM from Spotify's audio features, or None if Spotify won't provide it
   * (the endpoint is restricted for newer Spotify apps).
   */
  def getTempo(id: TrackId): F[Option[Double]] =
    authorised(GET(apiUri / "audio-features" / id.value)) { resp =>
      if resp.status.isSuccess then resp.as[Json].map(_.hcursor.downField("tempo").as[Double].toOption.filter(_ > 0))
      else Async[F].pure(None)
    }

  /**
   * Port of fetch-spotify-track-segments.ts
   */
  def getTrackAnalysis(id: TrackId): F[Vector[SpotifySegment]] =
    for
      token <- getOrRefresh
      req    = GET(apiUri / "audio-analysis" / id.value).withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
      json  <- client.expectOr[io.circe.Json](req){ errorRes =>
        errorRes.as[Json].flatMap { body =>
          logger.error(s"Spotify Exchange Failed. Body: $body") *>
            Async[F].raiseError(new RuntimeException(s"Spotify Error ${errorRes.status}: $body"))
        }
      }
      segments = json.hcursor.downField("segments").as[Vector[SpotifySegment]].getOrElse(Vector.empty)
    yield segments

object SpotifyClient:
  def serverResource[F[_] : Async: Network](deferred: Deferred[F, String]): Resource[F, org.http4s.server.Server] = {
    val dsl = new org.http4s.dsl.Http4sDsl[F] {}
    import dsl.*

    val routes = org.http4s.HttpRoutes.of[F] {
      case GET -> Root / "callback" :? CodeMatcher(code) =>
        for {
          _ <- deferred.complete(code)
          res <- Ok("Success! Return to your terminal.")
        } yield res
    }.orNotFound

    EmberServerBuilder.default[F].withHost( ipv4"127.0.0.1").withPort(port"7070").withHttpApp(routes).build
  }

  def resource[F[_] : Async: Network](client: Client[F], creds: SpotifyCredentials): Resource[F, SpotifyClient[F]] =
    for
      session <- Resource.eval(Ref.of[F, Option[SpotifySession]](None))
      authCodeDeferred <- Resource.eval(Deferred[F, String])
      _ <- serverResource(authCodeDeferred)
    yield new SpotifyClient[F](client, creds, session, authCodeDeferred)

  object CodeMatcher extends QueryParamDecoderMatcher[String]("code")