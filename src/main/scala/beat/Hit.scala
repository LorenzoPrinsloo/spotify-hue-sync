package beat

import scala.concurrent.duration.FiniteDuration

/** A moment that stood out, and how much: `strength` runs from 0 (barely) to 1 (as big as it gets). */
case class Hit(at: FiniteDuration, strength: Double)
