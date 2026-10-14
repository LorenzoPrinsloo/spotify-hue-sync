import cats.effect.*
import cats.syntax.all.*
import client.SpotifyClient
import fs2.Stream
import domain.config.{AppConfig, PulseSettings}
import domain.engine.{LocalPlayback, Palette, Pulse, Renderer}
import domain.hue.*
import domain.spotify.types.TrackId
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scala.concurrent.duration.*
import scala.util.Random

/** The tempo and palette chosen for one track. */
case class TrackShow(bpm: Double, palette: Palette)

class SyncEngine[F[_]: Async] private (
                               config: AppConfig,
                               zone: Zone,
                               spotify: SpotifyClient[F],
                               tracks: Ref[F, Map[TrackId, TrackShow]],
                               random: Random
                             ):

  private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]
  private val show = config.lightShow

  /** Tempo and palette for a track, looked up once and then cached. Logging only happens on a cache miss. */
  def showFor(trackId: TrackId): F[TrackShow] =
    tracks.get.map(_.get(trackId)).flatMap {
      case Some(existing) => Async[F].pure(existing)
      case None =>
        for {
          tempo <- spotify.getTempo(trackId).handleErrorWith { err =>
            logger.warn(s"Couldn't fetch the tempo for $trackId: $err") *> Async[F].pure(None)
          }
          bpm = tempo.getOrElse(show.defaultBpm) / show.beatsPerPulse
          generated <- Async[F].delay(Palette.random(show.palette)(using random).get)
          chosen <- tracks.modify { cache =>
            cache.get(trackId) match {
              case Some(existing) => (cache, existing)
              case None =>
                val created = TrackShow(bpm, generated)
                (cache.updated(trackId, created), created)
            }
          }
          source = tempo.fold(s"default ${show.defaultBpm} BPM")(t => s"track tempo $t BPM")
          _ <- logger.info(s"New show for $trackId: pulsing at ${chosen.bpm} BPM ($source), palette ${show.palette}:\n${chosen.palette.show}")
        } yield chosen
    }

  def run: Stream[F, Frame] =
    Stream.eval(SignallingRef[F, Option[LocalPlayback]](None)).flatMap { stateRef =>

      val poller = Stream.awakeEvery[F](config.pollingIntervalMs.millis)
        .evalMap { _ =>
          spotify.getPlaybackState.handleErrorWith { err =>
            logger.error(s"Spotify Poll Failed: $err") *> Async[F].pure(None)
          }
        }
        .evalMap(state => Clock[F].realTimeInstant.map(now => state.map(LocalPlayback(_, now))))
        .evalMap(stateRef.set)

      val renderer = Stream.awakeEvery[F](show.frameIntervalMs.millis)
        .evalMap(_ => stateRef.get)
        .evalMapAccumulate(Renderer.initial) { (rendered, playback) =>
          playback.filter(_.state.isPlaying) match {
            case Some(local) => playingFrame(local).map(Renderer.playing)
            case None        => Clock[F].monotonic.map(now => Renderer.paused(rendered, now, zone, show.pause))
          }
        }
        .map(_._2)

      renderer.merge(poller.drain)
        .zipWithIndex
        .map((frame, i) => frame.copy(sequence = (i % 256).toInt))
    }

  /** The frame for the current moment of a playing track. */
  private def playingFrame(local: LocalPlayback): F[Frame] =
    for {
      track <- showFor(local.state.trackId)
      now <- Clock[F].realTimeInstant
      latencyMs = java.time.Duration.between(local.receivedAt, now).toMillis
      positionMs = local.state.progress.toMillis + latencyMs + show.syncOffsetMs
    } yield SyncEngine.pulseFrame(zone, track.palette, track.bpm, positionMs, show.pulse)

object SyncEngine:

  def make[F[_]: Async](config: AppConfig, spotify: SpotifyClient[F], zone: Zone, random: Random = new Random()): F[SyncEngine[F]] =
    Ref.of[F, Map[TrackId, TrackShow]](Map.empty).map(new SyncEngine[F](config, zone, spotify, _, random))

  /**
   * One frame of the pulse for every channel in the zone. Light i takes palette colour i and starts its
   * pulse `stagger` of a beat after light i - 1, so the pulse travels across the zone as a wave.
   */
  def pulseFrame(zone: Zone, palette: Palette, bpm: Double, positionMs: Long, settings: PulseSettings): Frame = {
    val beats = positionMs / (60000.0 / bpm)
    val updates = zone.channels.zipWithIndex.map { (channel, i) =>
      val level = Pulse.level(beats - i * settings.stagger, settings)
      LightUpdate(channel, palette.getColor(i).scaleBrightness(Pulse.brightness(level, settings)))
    }
    Frame(zone.id, updates)
  }
