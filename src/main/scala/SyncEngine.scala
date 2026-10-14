import cats.effect.*
import cats.syntax.all.*
import beat.BeatState
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
                               random: Random,
                               beats: Stream[F, Option[BeatState]]
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

  /** Keeps `beatRef` up to date with the live beat, logging when it locks on or loses it; stops quietly on failure. */
  private def followBeats(beatRef: Ref[F, Option[BeatState]]): Stream[F, Nothing] =
    beats
      .evalTap {
        case Some(b) => beatRef.get.flatMap(prev => logger.info(f"Locked onto the beat at ${b.bpm}%.1f BPM").whenA(prev.isEmpty))
        case None    => beatRef.get.flatMap(prev => logger.info("Lost the beat; using Spotify timing until it's back").whenA(prev.isDefined))
      }
      .evalMap(beatRef.set)
      .handleErrorWith(err => Stream.eval(logger.warn(s"Beat detection stopped, using Spotify timing instead: ${err.getMessage}")))
      .drain

  def run: Stream[F, Frame] =
    Stream.eval((SignallingRef[F, Option[LocalPlayback]](None), Ref.of[F, Option[BeatState]](None)).tupled).flatMap { (stateRef, beatRef) =>

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
            case Some(local) => playingFrame(local, beatRef).map(Renderer.playing)
            case None        => Clock[F].monotonic.map(now => Renderer.paused(rendered, now, zone, show.pause))
          }
        }
        .map(_._2)

      renderer.merge(poller.drain).merge(followBeats(beatRef))
        .zipWithIndex
        .map((frame, i) => frame.copy(sequence = (i % 256).toInt))
    }

  /**
   * The frame for the current moment of a playing track. With a live beat, pulses follow it; otherwise they follow
   * Spotify's playback position at the track's tempo.
   */
  private def playingFrame(local: LocalPlayback, beatRef: Ref[F, Option[BeatState]]): F[Frame] =
    for {
      track <- showFor(local.state.trackId)
      beat <- beatRef.get
      monotonic <- Clock[F].monotonic
      now <- Clock[F].realTimeInstant
      pulses = beat match {
        case Some(b) => b.beatsAt(monotonic + show.audio.offsetMs.millis) / show.beatsPerPulse
        case None =>
          val latencyMs = java.time.Duration.between(local.receivedAt, now).toMillis
          SyncEngine.pulsesAt(local.state.progress.toMillis + latencyMs + show.syncOffsetMs, track.bpm)
      }
    } yield SyncEngine.pulseFrame(zone, track.palette, pulses, show.pulse)

object SyncEngine:

  def make[F[_]: Async](
    config: AppConfig,
    spotify: SpotifyClient[F],
    zone: Zone,
    random: Random = new Random(),
    beats: Stream[F, Option[BeatState]] = Stream.empty
  ): F[SyncEngine[F]] =
    Ref.of[F, Map[TrackId, TrackShow]](Map.empty).map(new SyncEngine[F](config, zone, spotify, _, random, beats))

  /** Pulses elapsed at a playback position, pulsing at `bpm`. */
  def pulsesAt(positionMs: Long, bpm: Double): Double = positionMs * bpm / 60000.0

  /**
   * One frame of the pulse for every channel in the zone, `pulses` pulses into the music. Light i takes palette
   * colour i and starts its pulse `stagger` of a pulse after light i - 1, so the pulse travels across the zone.
   */
  def pulseFrame(zone: Zone, palette: Palette, pulses: Double, settings: PulseSettings): Frame = {
    val updates = zone.channels.zipWithIndex.map { (channel, i) =>
      val level = Pulse.level(pulses - i * settings.stagger, settings)
      LightUpdate(channel, palette.getColor(i).scaleBrightness(Pulse.brightness(level, settings)))
    }
    Frame(zone.id, updates)
  }
