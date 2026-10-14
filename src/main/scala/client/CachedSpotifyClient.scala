package client

import cats.effect.*
import cats.syntax.all.*
import domain.spotify.*
import domain.spotify.types.TrackId
import org.http4s.client.Client
import org.typelevel.log4cats.slf4j.Slf4jLogger
import client.SpotifyClient
import fs2.io.net.Network

private class CachedSpotifyClient[F[_]: Async: Network](
  client: Client[F],
  creds: SpotifyCredentials,
  tokenRef: Ref[F, Option[String]],
  cache: Ref[F, Map[TrackId, Vector[SpotifySegment]]],
  authCodeDeferred: Deferred[F, String]
) extends SpotifyClient[F](client, creds, tokenRef, authCodeDeferred) {

  private val logger = Slf4jLogger.getLogger[F]

  override def getTrackAnalysis(trackId: TrackId): F[Vector[SpotifySegment]] =
    cache.get.flatMap { m =>
      m.get(trackId) match {
        case Some(segments) =>
          Async[F].pure(segments)
        case None =>
          for {
            _ <- logger.info(s"Cache miss for $trackId. Fetching from Spotify...")
            segments <- super.getTrackAnalysis(TrackId(trackId))
            _ <- cache.update(_ + (trackId -> segments))
          } yield segments
      }
    }
}

object CachedSpotifyClient {
  def resource[F[_]: Async: Network](client: Client[F], creds: SpotifyCredentials): Resource[F, SpotifyClient[F]] = {
    for {
      tokenRef         <- Resource.eval(Ref.of[F, Option[String]](None))
      authCodeDeferred <- Resource.eval(Deferred[F, String])
      segmentCache     <- Resource.eval(Ref.of[F, Map[TrackId, Vector[SpotifySegment]]](Map.empty))

      // We still need to start the Ember server here if you want it in the cache resource
      _ <- SpotifyClient.serverResource[F](authCodeDeferred)

    } yield new CachedSpotifyClient(client, creds, tokenRef, segmentCache, authCodeDeferred)
  }
}
