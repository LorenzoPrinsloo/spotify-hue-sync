package domain.config

import io.circe.Codec

/**
 * The swell of the colour lights: one every `beats` beats, peaking on the beat. As for the pulse, `attack` and
 * `sustain` are fractions of a swell and `decayCurve` shapes the fade after the peak; brightness runs from `restLevel`
 * up to `restLevel + peakBoost` times the colour's own. The defaults rise slowly, hold briefly and peak at the colour's
 * full brightness without overdriving it, so the whole swell stays visible.
 */
case class SwellSettings(
  beats: Double = 2.0,
  attack: Double = 0.45,
  sustain: Double = 0.1,
  decayCurve: Double = 2.0,
  restLevel: Double = 0.25,
  peakBoost: Double = 0.75
) derives Codec.AsObject {

  /** The swell as a pulse shape, one swell long. */
  def shape: PulseSettings = PulseSettings(0.0, attack, sustain, decayCurve, restLevel, peakBoost)
}
