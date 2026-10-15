package domain.engine

import beat.AudioReading
import domain.config.{ReactiveSettings, SwellSettings}
import scala.concurrent.duration.FiniteDuration

/**
 * How the music shapes the show at one moment: an overall brightness, a turn of the palette's hue, a white flash
 * across every light after a big hit, and a twinkle on one light after a treble onset. With live audio, each light
 * also has a role: `rhythm` is where the show is in the bar's kick pattern, `paletteShift` how many palette colours
 * the colour lights have moved along the palette, and `swell` how they swell.
 */
case class Effects(
  intensity: Double = 1.0,
  hueShift: Int = 0,
  flash: Double = 0.0,
  sparkle: Option[Sparkle] = None,
  roles: Vector[LightRole] = Vector.empty,
  rhythm: Option[Rhythm] = None,
  paletteShift: Double = 0.0,
  swell: SwellSettings = SwellSettings()
) {
  /** The given light's role; without live audio every light pulses. */
  def roleOf(light: Int): LightRole = roles.lift(light).getOrElse(LightRole.Pulse)

  /** How far the given light is pushed towards white. */
  def whiteFor(light: Int): Double =
    math.min(1.0, flash + sparkle.filter(_.light == light).fold(0.0)(_.amount))
}

/** A twinkle on one light, by `amount` towards white. */
case class Sparkle(light: Int, amount: Double)

/** The bar's kick pattern (see [[beat.Groove]]), and how many beats into the beat grid the show is. */
case class Rhythm(groove: Vector[Double], beats: Double)

object Effects {

  /** No reaction: the pulse exactly as configured. */
  val neutral: Effects = Effects()

  /** Spreads successive sparkles over the lights, so they don't just walk along the zone. */
  private val Scatter = 7919L

  def at(now: FiniteDuration, reading: AudioReading, lights: Int, settings: ReactiveSettings): Effects = {
    def fading(since: FiniteDuration, decayMs: Long): Double = {
      val elapsedMs = (now - since).toMillis.toDouble
      if (elapsedMs < 0) 1.0 else math.exp(-elapsedMs / decayMs)
    }
    val intensity = math.max(settings.quietest, math.min(settings.loudest, reading.energy))
    val hueShift = math.round(settings.colourShift * math.max(-1.0, math.min(1.0, reading.treble - 1.0))).toInt
    val flash = reading.hit.fold(0.0)(hit => settings.flash * hit.strength * fading(hit.at, settings.flashDecayMs))
    val roles = LightRole.assign(settings.roles, lights)
    val twinkling = roles.indices.filter(roles(_) != LightRole.Rhythm)
    val sparkle = reading.sparkle
      .filter(_ => twinkling.nonEmpty)
      .map(s => Sparkle(twinkling(Math.floorMod(reading.sparkles * Scatter, twinkling.size.toLong).toInt), settings.sparkle * s.strength * fading(s.at, settings.sparkleDecayMs)))
      .filter(_.amount > 0.005)
    val rhythm = reading.beat.filter(_ => reading.groove.nonEmpty).map(b => Rhythm(reading.groove, b.countAt(now)))
    Effects(intensity, hueShift, if (flash > 0.005) flash else 0.0, sparkle, roles, rhythm, reading.pitch * settings.colourReach, settings.colourSwell)
  }
}
