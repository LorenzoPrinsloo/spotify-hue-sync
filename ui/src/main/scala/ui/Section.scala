package ui

import domain.config.LightShowConfig

/**
 * A group of settings on the page, with a line on what they do. `unused` says why the settings change nothing with
 * the given settings (no light plays the roles they're for), or None while they're in use.
 */
final case class Section(
  title: String,
  intro: String,
  controls: List[Control],
  sliders: List[Slider],
  unused: LightShowConfig => Option[String] = _ => None
)
