package domain.config

import domain.engine.RGB
import eu.timepit.refined.api.Refined
import io.circe.Codec

/** What the lights do while nothing is playing: fade to `restingColour` over `fadeOutMs`, then hold it. */
case class PauseSettings(
  restingColour: RGB = RGB(Refined.unsafeApply(30), Refined.unsafeApply(20), Refined.unsafeApply(8)),
  fadeOutMs: Long = 2000
) derives Codec.AsObject
