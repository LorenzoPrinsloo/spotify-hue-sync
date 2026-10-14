package domain.engine

import domain.hue.types.LightId

case class SyncFrame(
  lightUpdates: Map[LightId, RGB]
)