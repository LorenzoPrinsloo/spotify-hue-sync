package ui

import domain.config.LightShowConfig

/**
 * A setting shown as a slider: its path in the config file, its label and help on the page, the range and step the
 * slider moves through, and how to read and change it.
 */
final case class Slider(
  key: String,
  label: String,
  help: String,
  min: Double,
  max: Double,
  step: Double,
  get: LightShowConfig => Double,
  set: (LightShowConfig, Double) => LightShowConfig
)
