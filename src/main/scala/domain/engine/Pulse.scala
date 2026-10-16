package domain.engine

import beat.Groove
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
   * Envelope level `beats` beats into the beat grid for a light following the bar's kick pattern: each sixteenth of
   * the bar gets its own pulse, as strong as the groove's weight there and timed like [[onBeat]], so its peak lands on
   * the sixteenth. A groove with a full kick on every beat gives exactly the beat pulse.
   */
  def rhythm(groove: Vector[Double], beats: Double, s: PulseSettings): Double = {
    val bar = Groove.BeatsPerBar.toDouble
    val inBar = beats - math.floor(beats / bar) * bar
    groove.indices.foldLeft(0.0) { (strongest, step) =>
      val rising = inBar - step.toDouble / Groove.StepsPerBeat + s.attack
      val phase = rising - math.floor(rising / bar) * bar
      if (phase < 1) math.max(strongest, groove(step) * level(phase, s)) else strongest
    }
  }

  /**
   * Brightness multiplier for an envelope level: `restLevel` at rest, `restLevel + peakBoost` at the peak, all scaled
   * by the music's `intensity` (1 when the show isn't reacting to live audio).
   */
  def brightness(level: Double, s: PulseSettings, intensity: Double = 1.0): Double = (s.restLevel + s.peakBoost * level) * intensity
}
