import cats.effect.*
import cats.syntax.all.*
import beat.AudioReading
import client.SpotifyClient
import fs2.Stream
import domain.config.{AppConfig, PulseSettings}
import domain.engine.{Effects, LocalPlayback, Palette, Pulse, RGB, Renderer}
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
                               audio: Stream[F, AudioReading]
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

  /**
   * Keeps `audioRef` up to date with what the music is doing, logging when the beat locks on or is lost. On failure
   * it clears the reading, so the show carries on with Spotify timing and no audio effects.
   */
  private def followAudio(audioRef: Ref[F, Option[AudioReading]]): Stream[F, Nothing] =
    audio
      .evalTap { reading =>
        audioRef.get.map(_.flatMap(_.beat)).flatMap { previous =>
          (previous, reading.beat) match {
            case (None, Some(b)) => logger.info(f"Locked onto the beat at ${b.bpm}%.1f BPM")
            case (Some(_), None) => logger.info("Lost the beat; using Spotify timing until it's back")
            case _               => Async[F].unit
          }
        }
      }
      .evalMap(reading => audioRef.set(Some(reading)))
      .handleErrorWith { err =>
        Stream.eval(audioRef.set(None) *> logger.warn(s"Listening stopped, using Spotify timing instead: ${err.getMessage}"))
      }
      .drain

  def run: Stream[F, Frame] =
    Stream.eval((SignallingRef[F, Option[LocalPlayback]](None), Ref.of[F, Option[AudioReading]](None)).tupled).flatMap { (stateRef, audioRef) =>

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
            case Some(local) => playingFrame(local, audioRef).map(Renderer.playing)
            case None        => Clock[F].monotonic.map(now => Renderer.paused(rendered, now, zone, show.pause))
          }
        }
        .map(_._2)

      renderer.merge(poller.drain).merge(followAudio(audioRef))
        .zipWithIndex
        .map((frame, i) => frame.copy(sequence = (i % 256).toInt))
    }

  /**
   * The frame for the current moment of a playing track. With a live beat, pulses follow it; otherwise they follow
   * Spotify's playback position at the track's tempo. With live audio, the effects follow the music too.
   */
  private def playingFrame(local: LocalPlayback, audioRef: Ref[F, Option[AudioReading]]): F[Frame] =
    for {
      track <- showFor(local.state.trackId)
      reading <- audioRef.get
      monotonic <- Clock[F].monotonic
      now <- Clock[F].realTimeInstant
      heardAt = monotonic + show.audio.offsetMs.millis
      pulses = reading.flatMap(_.beat) match {
        case Some(b) => b.beatsAt(heardAt) / show.beatsPerPulse
        case None =>
          val latencyMs = java.time.Duration.between(local.receivedAt, now).toMillis
          SyncEngine.pulsesAt(local.state.progress.toMillis + latencyMs + show.syncOffsetMs, track.bpm)
      }
      effects = reading
        .filter(_ => show.reactive.enabled)
        .fold(Effects.neutral)(r => Effects.at(heardAt, r, zone.channels.size, show.reactive))
    } yield SyncEngine.pulseFrame(zone, track.palette, pulses, show.pulse, effects)

object SyncEngine:

  def make[F[_]: Async](
    config: AppConfig,
    spotify: SpotifyClient[F],
    zone: Zone,
    random: Random = new Random(),
    audio: Stream[F, AudioReading] = Stream.empty
  ): F[SyncEngine[F]] =
    Ref.of[F, Map[TrackId, TrackShow]](Map.empty).map(new SyncEngine[F](config, zone, spotify, _, random, audio))

  /** Pulses elapsed at a playback position, pulsing at `bpm`. */
  def pulsesAt(positionMs: Long, bpm: Double): Double = positionMs * bpm / 60000.0

  private val White: RGB = RGB.safe(255, 255, 255).get

  /**
   * One frame of the pulse for every channel in the zone, `pulses` pulses into the music. Light i takes palette
   * colour i and starts its pulse `stagger` of a pulse after light i - 1, so the pulse travels across the zone.
   * The effects scale the brightness, turn the palette's hue, and push lights towards white for flashes and sparkles.
   */
  def pulseFrame(zone: Zone, palette: Palette, pulses: Double, settings: PulseSettings, effects: Effects = Effects.neutral): Frame = {
    val updates = zone.channels.zipWithIndex.map { (channel, i) =>
      val level = Pulse.level(pulses - i * settings.stagger, settings)
      val colour = if (effects.hueShift == 0) palette.getColor(i) else palette.getColor(i).rotateHue(effects.hueShift)
      val lit = colour.scaleBrightness(Pulse.brightness(level, settings, effects.intensity))
      val white = effects.whiteFor(i)
      LightUpdate(channel, if (white <= 0) lit else lit.mix(White, white))
    }
    Frame(zone.id, updates)
  }
