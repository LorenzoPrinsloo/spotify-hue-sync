import cats.effect.*
import cats.syntax.all.*
import beat.{AudioReading, BeatState}
import client.SpotifyClient
import fs2.Stream
import domain.config.{AppConfig, PulseSettings}
import domain.engine.{Effects, LightRole, LocalPlayback, Palette, Pulse, RGB, Renderer}
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
   * Keeps `audioRef` up to date with what the music is doing, logging when the beat locks on, is lost or changes
   * tempo. On failure it clears the reading, so the show carries on with Spotify timing and no audio effects.
   */
  private def followAudio(audioRef: Ref[F, Option[AudioReading]]): Stream[F, Nothing] =
    audio
      .evalMapAccumulate(Option.empty[Double]) { (logged, reading) =>
        val (remembered, news) = SyncEngine.beatNews(logged, reading.beat)
        (news.traverse_(logger.info(_)) *> audioRef.set(Some(reading))).as((remembered, ()))
      }
      .drain
      .handleErrorWith { err =>
        Stream.exec(audioRef.set(None) *> logger.warn(s"Listening stopped, using Spotify timing instead: ${err.getMessage}"))
      }

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

  /** How far the tempo has to move from the last one logged before it's logged again. */
  val TempoChangeToLog: Double = 0.03

  /**
   * What to log about the beat, given the tempo logged last and the latest beat: that it locked on, that it was lost,
   * or that the tempo moved by more than `TempoChangeToLog`. Returns the tempo to remember and the message, if any.
   */
  def beatNews(logged: Option[Double], beat: Option[BeatState]): (Option[Double], Option[String]) =
    (logged, beat) match {
      case (None, Some(b)) => (Some(b.bpm), Some(f"Locked onto the beat at ${b.bpm}%.1f BPM (confidence ${b.confidence}%.1f)"))
      case (Some(_), None) => (None, Some("Lost the beat; using Spotify timing until it's back"))
      case (Some(was), Some(b)) if math.abs(b.bpm - was) / was > TempoChangeToLog =>
        (Some(b.bpm), Some(f"Tempo changed from $was%.1f to ${b.bpm}%.1f BPM (confidence ${b.confidence}%.1f)"))
      case _ => (logged, None)
    }

  /** Pulses elapsed at a playback position, pulsing at `bpm`. */
  def pulsesAt(positionMs: Long, bpm: Double): Double = positionMs * bpm / 60000.0

  private val White: RGB = RGB.safe(255, 255, 255).get

  /**
   * One frame of the show for every channel in the zone, `pulses` pulses into the music. Light i takes palette colour
   * i, and what it does depends on its role:
   *  - a pulse light peaks `stagger` of a pulse after light i - 1 (light 0 on every whole pulse), so the pulse travels
   *    across the zone, and its hue turns with the treble;
   *  - a rhythm light pulses with the bar's kick pattern, or on every pulse until there's a live beat;
   *  - a colour light swells once every `swell.beats` beats, peaking on the beat, and moves along the palette as the
   *    music goes higher or lower.
   * The effects scale the brightness and push lights towards white for flashes and sparkles.
   */
  def pulseFrame(zone: Zone, palette: Palette, pulses: Double, settings: PulseSettings, effects: Effects = Effects.neutral): Frame = {
    val updates = zone.channels.zipWithIndex.map { (channel, i) =>
      val (brightness, colour) = effects.roleOf(i) match {
        case LightRole.Pulse =>
          val own = palette.getColor(i)
          val level = Pulse.onBeat(pulses - i * settings.stagger, settings)
          (Pulse.brightness(level, settings, effects.intensity), if (effects.hueShift == 0) own else own.rotateHue(effects.hueShift))
        case LightRole.Rhythm =>
          val level = effects.rhythm.fold(Pulse.onBeat(pulses, settings))(r => Pulse.rhythm(r.groove, r.beats, settings))
          (Pulse.brightness(level, settings, effects.intensity), palette.getColor(i))
        case LightRole.Colour =>
          // Counted along the beat grid when there's a live beat, so every other beat stays the same beat as the tracker re-estimates.
          val beats = effects.rhythm.fold(pulses)(_.beats)
          val shape = effects.swell.shape
          (Pulse.brightness(Pulse.onBeat(beats / effects.swell.beats, shape), shape, effects.intensity), palette.along(i, effects.paletteShift))
      }
      val lit = colour.scaleBrightness(brightness)
      val white = effects.whiteFor(i)
      LightUpdate(channel, if (white <= 0) lit else lit.mix(White, white))
    }
    Frame(zone.id, updates)
  }
