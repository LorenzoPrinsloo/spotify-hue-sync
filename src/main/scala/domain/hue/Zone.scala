package domain.hue

/** An entertainment zone and its channel IDs, in the order the pulse travels through them. */
case class Zone(id: String, channels: List[Int])
