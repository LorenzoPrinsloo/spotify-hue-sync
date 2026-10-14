import cats.effect.*
import cats.syntax.all.*
import client.SpotifyClient
import fs2.Stream
import domain.*
import domain.hue.*
import domain.spotify.*
import domain.config.AppConfig
import domain.engine.Order.Flat
import domain.engine.PaletteType.{Analogous, AnalogousComplementary}
import domain.engine.{LocalPlayback, Palette, RGB}
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import eu.timepit.refined.*
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto.*
import eu.timepit.refined.numeric.Interval
import java.time.Instant
import scala.concurrent.duration.*
import scala.util.Random
import domain.engine.RGB.ByteInt

class SyncEngine[F[_]: Async] private (
                               config: AppConfig,
                               spotify: SpotifyClient[F],
                               paletteCache: Ref[F, Map[String, Palette]]
                             ):

  private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  /**
   * Every track pulses at the same tempo; the palette is generated once per track.
   * Logging only happens on a cache miss.
   */
  private def getTrackResources(trackId: String): F[(Double, Palette)] =
    paletteCache.get.map(_.get(trackId)).flatMap {
      case Some(existing) =>
        Async[F].pure((SyncEngine.PulseBpm, existing))
      case None =>
        for {
          generated <- Async[F].delay(Palette.random(Analogous).get)
          palette <- paletteCache.modify { cache =>
            cache.get(trackId) match {
              case Some(existing) => (cache, existing)
              case None           => (cache.updated(trackId, generated), generated)
            }
          }
          _ <- logger.info(s"New Palette for $trackId (BPM: ${SyncEngine.PulseBpm}):\n${palette.show}")
        } yield (SyncEngine.PulseBpm, palette)
    }

  def run: Stream[F, Frame] =
    Stream.eval(SignallingRef[F, Option[LocalPlayback]](None)).flatMap { stateRef =>

      val poller = Stream.awakeEvery[F](config.pollingIntervalMs.millis)
        .evalMap { _ =>
          spotify.getPlaybackState.handleErrorWith { err =>
            logger.error(s"Spotify Poll Failed: $err") *> Async[F].pure(None)
          }
        }
        .map(_.map(s => LocalPlayback(s, Instant.now())))
        .evalMap(sp => stateRef.set(sp))

      val renderer = Stream.awakeEvery[F](16.millis) // ~60fps for smooth pulsing
        .evalMap(_ => stateRef.get)
        .unNone
        .flatMap { local =>
          if (!local.state.isPlaying) Stream.empty
          else Stream.eval(calculateBpmFrame(local)).unNone
        }

      renderer.merge(poller.drain)
    }

  /**
   * Calculates the frame based on tempo and time elapsed since song start.
   */
  private def calculateBpmFrame(local: LocalPlayback): F[Option[Frame]] =
    for {
      resources <- getTrackResources(local.state.trackId)
      (bpm, palette) = resources

      // Wrap side-effecting time calls in Sync/Async delay
      now <- Async[F].delay(Instant.now())

      // Spotify 'timestamp' is when the progress was measured
      latency = java.time.Duration.between(local.receivedAt, now)
      latencyMs = latency.toMillis

      // Adjust this nudge if the lights are ahead/behind the audio
      manualOffset = -150
      realProgressMs = local.state.progress.toMillis + latencyMs + manualOffset

      frame = SyncEngine.pulseFrame(config.hue.zoneId, bpm, realProgressMs, palette)
    } yield Some(frame)

object SyncEngine:
  private val PulseBpm: Double = 20.0

  def make[F[_]: Async](config: AppConfig, spotify: SpotifyClient[F]): F[SyncEngine[F]] =
    Ref.of[F, Map[String, Palette]](Map.empty).map(new SyncEngine[F](config, spotify, _))

  def pulseFrame(zoneId: String, bpm: Double, progressMs: Long, palette: Palette): Frame = {
    val beatDurationMs = 60000.0 / bpm
    val numLights = 7

    // How much to delay each light (0.1 means each light starts 10% later than the previous)
    // Adjust this: 0.1 is subtle, 0.2 is a very obvious "wave"
    val staggerOffset = 0.15

    val updates = (0 until numLights).map { channelId =>
      val baseColor = palette.getColor(channelId)

      // 1. Calculate the shifted progress for this specific light
      // We don't slice the beat; we just shift the "Start Time" of the pulse
      val totalProgress = progressMs.toDouble / beatDurationMs
      val lightOffset = channelId * staggerOffset

      // Modulo 1.0 gives us where this specific light is in its pulse cycle (0.0 to 1.0)
      val individualProgress = (totalProgress - lightOffset) % 1.0
      val normalizedProgress = if (individualProgress < 0) individualProgress + 1.0 else individualProgress

      // 2. Pulse math (identical to before, but now it lasts the whole beat)
      val attack = 0.20 // 20% of beat spent "swelling" up
      val sustain = 0.10 // 10% of beat staying at peak
      val pulse = if (normalizedProgress < attack) {
        // 1. Swell: Linear or Sine ramp up
        normalizedProgress / attack
      } else if (normalizedProgress < attack + sustain) {
        // 2. Plateau: Stay at max
        1.0
      } else {
        // 3. Gradual Decay: 
        // Lowering the Power (from 4.0 to 1.5 or 2.0) makes the fade linger longer.
        val decayStart = attack + sustain
        val decayProgress = (normalizedProgress - decayStart) / (1.0 - decayStart)
        Math.pow(1.0 - decayProgress, 2.0) // 2.0 is much "softer" than 4.0
      }

      def boost(v: ByteInt): ByteInt = {
        val peakBoost = 2.5
        val floor = 0.15
        val multiplier = floor + (peakBoost * pulse)
        Refined.unsafeApply(Math.max(0, Math.min(255, (v.value * multiplier).toInt)))
      }

      LightUpdate(channelId, RGB(boost(baseColor.r), boost(baseColor.g), boost(baseColor.b)))
    }.toList

    Frame(zoneId, updates)
  }
