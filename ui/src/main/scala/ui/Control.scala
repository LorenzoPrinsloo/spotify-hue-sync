package ui

/** Settings in a section that aren't sliders, each with its own control, shown before the section's sliders. */
enum Control derives CanEqual {
  case Area, Roles, Reactions, Palette, AlbumCoverColours, SongPalette, RestingColour, FixedSettings

  /** The settings this control shows, by their path in the config file. */
  def keys: List[String] = this match {
    case Area => List("area")
    case Roles => List("reactive.roles")
    case Reactions => List("reactive.enabled")
    case Palette => List("palette")
    case AlbumCoverColours => List("album-cover-colours")
    case SongPalette => Nil
    case RestingColour => List("pause.resting-colour")
    case FixedSettings => List("beat-source", "audio.device", "frame-interval-ms")
  }
}
