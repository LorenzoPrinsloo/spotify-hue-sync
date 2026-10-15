package domain.config

import io.circe.Codec

/**
 * The light show's settings as the settings page sees them: `pending` takes effect when the next song starts,
 * `applied` is what's playing now, and `fromFile` is what the config file set.
 */
case class SettingsView(pending: LightShowConfig, applied: LightShowConfig, fromFile: LightShowConfig) derives Codec.AsObject
