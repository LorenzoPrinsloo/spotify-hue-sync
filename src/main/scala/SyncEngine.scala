import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import beat.{AudioReading, BeatState, TempoNews}
import client.{AlbumCovers, CoverError, SpotifyClient, SpotifyTlsFailure}
import fs2.Stream
import domain.config.{AppConfig, LightShowConfig, PulseSettings}
import domain.engine.{Effects, LightRole, LocalPlayback, Palette, PaletteType, Pulse, RGB, Renderer}
import domain.hue.*
import domain.spotify.types.TrackId
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scala.concurrent.duration.*
import scala.util.Random
import scala.util.control.NonFatal
import settings.LiveSettings

/**
 * The track's tempo from Spotify, if it gave one, and the palette, settings and lights the show plays it with. `base` is
 * the colour the palette was built from, kept so a new palette type can be built from the same colour.
 */
case class TrackShow(tempo: Option[Double], palette: Palette, settings: LightShowConfig, zone: Zone, base: RGB) {
  def bpm: Double = tempo.getOrElse(settings.defaultBpm) / settings.beatsPerPulse
}

class SyncEngine[F[_]: Async] private (
  config: AppConfig,
  live: LiveSettings[F],
  zone: Zone,
  spotify: SpotifyClient[F],
  lights: Lights[F],
  current: Ref[F, Option[(TrackId, TrackShow)]],
  random: Random,
  audio: Stream[F, AudioReading],
  covers: AlbumCovers[F]
):

  private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  private def randomBase: F[RGB] =
    Async[F].delay(RGB.random(using random))

  private def paletteFrom(base: RGB, paletteType: PaletteType): F[Palette] =
    Async[F].fromOption(Palette.from(base, paletteType), new IllegalStateException(s"Couldn't make a $paletteType palette"))

  private def coverFailure(trackId: TrackId, url: String, err: Throwable): F[Unit] = {
    val detail = err match {
      case e: CoverError =>
        val extra = e match {
          case CoverError.Refused(_, status) => Map("status" -> status.code.toString)
          case CoverError.TimedOut(_, after) => Map("waited_ms" -> after.toMillis.toString)
          case _ => Map.empty[String, String]
        }
        extra + ("reason" -> e.reason)
      case _ => Map("reason" -> "failed")
    }
    logger.warn(Map("track_id" -> trackId.toString, "cover_url" -> url) ++ detail)(
      s"Couldn't take the colours from the album cover for $trackId, using a random palette: ${err.getMessage}"
    )
  }

  private def tempoFor(trackId: TrackId): F[Option[Double]] =
    spotify.getTempo(trackId).handleErrorWith { err =>
      (err match {
        case _: SpotifyTlsFailure => logger.error(s"Couldn't fetch the tempo for $trackId: $err")
        case _ => logger.warn(s"Couldn't fetch the tempo for $trackId: $err")
      }) *> Async[F].pure(None)
    }

  private def baseDescription(fromCover: Option[RGB], base: RGB): String =
    fromCover.fold(s"random base ${base.hex}")(_ => s"base ${base.hex} from the album cover")

  /** The cover's base colour, if the setting is on and the cover arrives in time and has one. Any failure is logged and gives None. */
  private def coverBase(trackId: TrackId, cover: Option[String], settings: LightShowConfig): F[Option[RGB]] =
    cover.filter(_ => settings.albumCoverColours).fold(Async[F].pure(Option.empty[RGB])) { url =>
      covers.baseColour(url).start.flatMap { fiber =>
        fiber.joinWithNever
          .onCancel(fiber.cancel.start.void)
          .timeoutTo(SyncEngine.CoverWait, Async[F].raiseError[Option[RGB]](CoverError.TimedOut(url, SyncEngine.CoverWait)))
      }.handleErrorWith { case NonFatal(err) => coverFailure(trackId, url, err).as(None) }
    }

  /** The lights to play `settings` on: the area they name, or else the ones `before` played on (or the first zone). */
  private def zoneFor(settings: LightShowConfig, before: Option[TrackShow]): F[Zone] = {
    val fallback = before.fold(zone)(_.zone)
    settings.area match {
      case None => Async[F].pure(fallback)
      case Some(name) if before.exists(_.settings.area.contains(name)) => Async[F].pure(fallback)
      case Some(name) =>
        lights.zone(name).attempt.flatMap {
          case Right(Some(found)) => logger.info(s"Playing on the $name area").as(found)
          case Right(None) => logger.warn(s"There's no $name area on the bridge; staying where the show is").as(fallback)
          case Left(err) => logger.warn(s"Couldn't read the $name area from the bridge, staying where the show is: $err").as(fallback)
        }
    }
  }

  /**
   * The show for a track. A new track takes up the latest settings, its area's lights and a new palette; the show
   * then stays the same until the track changes, so changes from the settings page wait for the next song.
   */
  def showFor(trackId: TrackId, cover: Option[String] = None): F[TrackShow] =
    current.get.flatMap {
      case Some((playing, show)) if playing == trackId => Async[F].pure(show)
      case before =>
        for {
          settings <- live.startSong
          (tempo, fromCover) <- Async[F].both(tempoFor(trackId), coverBase(trackId, cover, settings))
          base <- fromCover.fold(randomBase)(Async[F].pure)
          palette <- paletteFrom(base, settings.palette)
          lit <- zoneFor(settings, before.map(_._2))
          created = TrackShow(tempo, palette, settings, lit, base)
          _ <- current.set(Some(trackId -> created))
          source = tempo.fold(s"default ${settings.defaultBpm} BPM")(t => s"track tempo $t BPM")
          _ <- logger.info(
            s"New show for $trackId: pulsing at ${created.bpm} BPM ($source), palette ${settings.palette}, ${baseDescription(fromCover, base)}:\n${palette.show}"
          )
        } yield created
    }

  /** The palette of the song playing now, if one has started. */
  def palette: F[Option[Palette]] = current.get.map(_.map(_._2.palette))

  /**
   * Takes up the latest settings now rather than at the next song, moving to another area if they name one. The palette
   * only changes if its type did, and then it's built from the colour the song started with.
   */
  def applyNow: F[Unit] =
    for {
      settings <- live.startSong
      playing <- current.get
      _ <- playing.traverse_ { (trackId, show) =>
        val palette = if (show.settings.palette == settings.palette) Async[F].pure(show.palette) else paletteFrom(show.base, settings.palette)
        (palette, zoneFor(settings, Some(show))).flatMapN((p, lit) =>
          current.set(Some(trackId -> show.copy(palette = p, settings = settings, zone = lit)))
        )
      }
    } yield ()

  /**
   * Keeps `audioRef` up to date with what the music is doing, logging when the beat locks on, is lost or changes
   * tempo. On failure it clears the reading, so the show carries on with Spotify timing and no audio effects.
   */
  private def followAudio(audioRef: Ref[F, Option[AudioReading]]): Stream[F, Nothing] =
    audio
      .evalMapAccumulate(Option.empty[Double]) { (logged, reading) =>
        val (remembered, news) = SyncEngine.beatNews(logged, reading.beat)
        ((news.toList ++ reading.news.map(SyncEngine.tempoNewsLine)).traverse_(logger.info(_)) *> audioRef.set(Some(reading))).as((remembered, ()))
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

      val renderer = Stream.awakeEvery[F](config.lightShow.frameIntervalMs.millis)
        .evalMap(_ => stateRef.get)
        .evalMapAccumulate(Renderer.initial) { (rendered, playback) =>
          playback.filter(_.state.isPlaying).fold(
            (Clock[F].monotonic, live.applied, current.get).mapN { (now, show, playing) =>
              Renderer.paused(rendered, now, playing.fold(zone)(_._2.zone), show.pause)
            }
          )(local => playingFrame(local, audioRef).map(Renderer.playing))
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
      track <- showFor(local.state.trackId, local.state.albumCoverUrl)
      show = track.settings
      reading <- audioRef.get
      monotonic <- Clock[F].monotonic
      now <- Clock[F].realTimeInstant
      heardAt = monotonic + show.audio.offsetMs.millis
      pulses = reading.flatMap(_.beat).fold {
        val latencyMs = java.time.Duration.between(local.receivedAt, now).toMillis
        SyncEngine.pulsesAt(local.state.progress.toMillis + latencyMs + show.syncOffsetMs, track.bpm)
      }(b => b.beatsAt(heardAt) / show.beatsPerPulse)
      effects = reading
        .filter(_ => show.reactive.enabled)
        .fold(Effects.neutral)(r => Effects.at(heardAt, r, track.zone.channels.size, show.reactive))
    } yield SyncEngine.pulseFrame(track.zone, track.palette, pulses, show.pulse, effects)

object SyncEngine:

  def make[F[_]: Async](
    config: AppConfig,
    live: LiveSettings[F],
    spotify: SpotifyClient[F],
    zone: Zone,
    lights: Lights[F],
    random: Random = new Random(),
    audio: Stream[F, AudioReading] = Stream.empty,
    covers: Option[AlbumCovers[F]] = None
  ): F[SyncEngine[F]] =
    Ref.of[F, Option[(TrackId, TrackShow)]](None).map(
      new SyncEngine[F](config, live, zone, spotify, lights, _, random, audio, covers.getOrElse(AlbumCovers.none[F]))
    )

  /** The longest a song's first frame waits for its album cover. */
  val CoverWait: FiniteDuration = 1.second

  /** How far the tempo has to move from the last one logged before it's logged again. */
  private val TempoChangeToLog: Double = 0.03

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

  /** The log line for a tempo decision: both tempos and the ratio of the new one to the old. */
  def tempoNewsLine(news: TempoNews): String =
    news match {
      case TempoNews.KickMoved(from, to) =>
        f"The kick fits $to%.1f BPM better than $from%.1f BPM (ratio ${to / from}%.2f); using $to%.1f BPM"
      case TempoNews.FollowedRelated(from, to) =>
        f"Followed a related tempo from $from%.1f to $to%.1f BPM (ratio ${to / from}%.2f) once it had held"
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
          (effects.rhythmLight(pulses, settings), palette.getColor(i))
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
