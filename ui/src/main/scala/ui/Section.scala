package ui

/** A group of settings on the page, with a line on what they do. */
final case class Section(title: String, intro: String, controls: List[Control], sliders: List[Slider])
