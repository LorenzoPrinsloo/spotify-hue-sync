package domain.config

import cats.effect.kernel.Sync
import domain.spotify.SpotifyCredentials
import domain.config.LightShowConfigReaders.given
import pureconfig.*

case class AppConfig(
  spotify: SpotifyCredentials,
  hue: HueConfig,
  pollingIntervalMs: Int = 2000,
  lightShow: LightShowConfig = LightShowConfig(),
  ui: UiConfig = UiConfig()
) derives ConfigReader
object AppConfig {
  def load[F[_]: Sync](source: ConfigSource): F[AppConfig] =
    Sync[F].blocking(source.loadOrThrow[AppConfig])
}
