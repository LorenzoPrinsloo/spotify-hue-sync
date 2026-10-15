package ui

/** Settings in a section that aren't sliders, each with its own control, shown before the section's sliders. */
enum Control derives CanEqual {
  case Area, Roles, Reactions, Palette, SongPalette, RestingColour, FixedSettings
}
