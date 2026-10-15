package domain.config

import domain.hue.HueCredentials
import pureconfig.ConfigReader

/** The bridge's address and keys, and optionally the id of the entertainment area to play on (`light-show.area` names one instead). */
case class HueConfig(creds: HueCredentials, zoneId: Option[String] = None) derives ConfigReader
