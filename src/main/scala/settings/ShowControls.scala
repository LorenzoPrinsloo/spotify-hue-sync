package settings

import domain.engine.RGB
import domain.hue.MusicArea

/** What the settings page asks of the running show. */
trait ShowControls[F[_]] {

  /** Takes up the pending settings now rather than at the next song. */
  def applyNow: F[Unit]

  /** The bridge's entertainment areas. */
  def areas: F[List[MusicArea]]

  /** The palette of the song playing now, in the order the lights take it; empty before the first song. */
  def palette: F[Vector[RGB]]
}
