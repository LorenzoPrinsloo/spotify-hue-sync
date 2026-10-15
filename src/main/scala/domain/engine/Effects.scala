package domain.engine

import beat.AudioReading
import domain.config.ReactiveSettings
import scala.concurrent.duration.FiniteDuration

/**
 * How the music shapes the show at one moment: an overall brightness, a turn of the palette's hue, a white flash
 * across every light after a big hit, and a twinkle on one light after a treble onset.
 */
case class Effects(intensity: Double = 1.0, hueShift: Int = 0, flash: Double = 0.0, sparkle: Option[Sparkle] = None) {

  /** How far the given light is pushed towards white. */
  def whiteFor(light: Int): Double =
    math.min(1.0, flash + sparkle.filter(_.light == light).fold(0.0)(_.amount))
}

/** A twinkle on one light, by `amount` towards white. */
case class Sparkle(light: Int, amount: Double)

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
    val sparkle = reading.sparkle
      .filter(_ => lights > 0)
      .map(s => Sparkle(Math.floorMod(reading.sparkles * Scatter, lights.toLong).toInt, settings.sparkle * s.strength * fading(s.at, settings.sparkleDecayMs)))
      .filter(_.amount > 0.005)
    Effects(intensity, hueShift, if (flash > 0.005) flash else 0.0, sparkle)
  }
}
