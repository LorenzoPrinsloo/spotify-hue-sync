package domain.config

/** Shape of one pulse. `attack` and `sustain` are fractions of a pulse; brightness runs from `restLevel` up to `restLevel + peakBoost`. */
case class PulseSettings(
  stagger: Double = 0.15,
  attack: Double = 0.2,
  sustain: Double = 0.1,
  decayCurve: Double = 2.0,
  restLevel: Double = 0.15,
  peakBoost: Double = 2.5
)
