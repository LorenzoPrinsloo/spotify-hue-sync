package domain.config

import domain.hue.HueCredentials
import pureconfig.ConfigReader

case class HueConfig(zoneId: String, creds: HueCredentials) derives ConfigReader
