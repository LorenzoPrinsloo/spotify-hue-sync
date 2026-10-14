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
  tokenRef: Ref[F, Option[String]],
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
      _ <- tokenRef.set(Some(resp.access_token))
    yield resp.refresh_token.getOrElse("")


  /**
   * Helper to always get a valid token.
   */
  def getOrRefresh: F[String] =
    tokenRef.get.flatMap:
      case Some(t) => Async[F].pure(t)
      case None    => initialAuth

  /**
   * Port of fetch-spotify-playback-state.ts
   */
  def getPlaybackState: F[Option[PlaybackState]] =
    for
      token <- getOrRefresh
      req    = GET(apiUri / "me" / "player").withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
      res   <- client.run(req).use: resp =>
        if resp.status == Status.NoContent || resp.status == Status.Unauthorized then Async[F].pure(None)
        else resp.as[PlaybackState].map(Some(_))
    yield res

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
      tokenRef <- Resource.eval(Ref.of[F, Option[String]](None))
      authCodeDeferred <- Resource.eval(Deferred[F, String])
      _ <- serverResource(authCodeDeferred)
    yield new SpotifyClient[F](client, creds, tokenRef, authCodeDeferred)

  object CodeMatcher extends QueryParamDecoderMatcher[String]("code")