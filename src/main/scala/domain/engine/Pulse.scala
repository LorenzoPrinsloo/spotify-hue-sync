package domain.engine

import domain.config.PulseSettings

object Pulse {

  /** Envelope level in [0, 1] at `phase` pulses since the start; only the fractional part matters. */
  def level(phase: Double, s: PulseSettings): Double = {
    val p = phase - math.floor(phase)
    val decayStart = s.attack + s.sustain
    if (p < s.attack) p / s.attack
    else if (p < decayStart) 1.0
    else math.pow(1.0 - (p - decayStart) / (1.0 - decayStart), s.decayCurve)
  }

  /**
   * Envelope level `phase` pulses after a pulse lands, timed so the attack comes just before it and the peak lands on
   * it: rising on the beat instead would leave the peak a fifth of a beat late, more on slower songs.
   */
  def onBeat(phase: Double, s: PulseSettings): Double = level(phase + s.attack, s)

  /**
   * Brightness multiplier for an envelope level: `restLevel` at rest, `restLevel + peakBoost` at the peak, all scaled
   * by the music's `intensity` (1 when the show isn't reacting to live audio).
   */
  def brightness(level: Double, s: PulseSettings, intensity: Double = 1.0): Double =
    (s.restLevel + s.peakBoost * level) * intensity
}
