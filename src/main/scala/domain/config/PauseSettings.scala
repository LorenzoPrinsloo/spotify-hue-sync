package domain.config

import domain.engine.RGB

/** What the lights do while nothing is playing: fade to `restingColour` over `fadeOutMs`, then hold it. */
case class PauseSettings(
  restingColour: RGB = RGB.safe(30, 20, 8).get,
  fadeOutMs: Long = 2000
)
