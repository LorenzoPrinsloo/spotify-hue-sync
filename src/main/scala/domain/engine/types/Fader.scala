package domain.engine.types

/**
 * The "Fader" function type from the original project.
 * Takes a value 0.0 -> 1.0 (progress through segment)
 * Returns 0.0 -> 1.0 (intensity multiplier)
 */
opaque type Fader = Double => Double
object Fader:
  def apply(f: Double => Double): Fader = f
  extension (f: Fader) def run(d: Double): Double = f(d)