package domain.config

import cats.effect.kernel.Sync
import domain.spotify.SpotifyCredentials
import pureconfig.*

case class AppConfig(
  spotify: SpotifyCredentials,
  hue: HueConfig,
  pollingIntervalMs: Int = 2000,
  lightShow: LightShowConfig = LightShowConfig()
) derives ConfigReader
object AppConfig {
  def load[F[_] : Sync]: F[AppConfig] =
    Sync[F].blocking(ConfigSource.default.loadOrThrow[AppConfig])
}