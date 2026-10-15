package domain.config

import pureconfig.ConfigReader

/** Where the settings page is served: `127.0.0.1` keeps it to this computer, `0.0.0.0` opens it to the home network. */
case class UiConfig(enabled: Boolean = true, host: String = "127.0.0.1", port: Int = 7071) derives ConfigReader
