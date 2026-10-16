package settings

import cats.effect.{Concurrent, Ref}
import cats.syntax.all.*
import domain.config.{LightShowConfig, SettingsProblems, SettingsView}
import domain.hue.MusicArea

/**
 * The light show's settings for this session. They start as the config file's; the settings page changes the pending
 * ones, and the engine takes those up as each song starts, so the settings never change part way through a song.
 */
final class LiveSettings[F[_]: Concurrent] private (
  val fromFile: LightShowConfig,
  pending: Ref[F, LightShowConfig],
  playing: Ref[F, LightShowConfig],
  known: Ref[F, List[MusicArea]]
) {

  def view: F[SettingsView] = (pending.get, playing.get).mapN(SettingsView(_, _, fromFile))

  /** The settings the show is playing with now. */
  def applied: F[LightShowConfig] = playing.get

  /** Remembers the areas on the bridge, so a change to another area can be checked against them. */
  def areasFound(areas: List[MusicArea]): F[Unit] = known.set(areas)

  /**
   * Sets the settings for the next song, unless they can't work, name an area the bridge doesn't have, or change
   * something that only changes on a restart.
   */
  def update(next: LightShowConfig): F[Either[SettingsProblems, SettingsView]] =
    known.get.flatMap { areas =>
      LiveSettings.problems(fromFile, next, areas) match {
        case Nil => pending.set(next) *> view.map(Right(_))
        case found => Concurrent[F].pure(Left(SettingsProblems(found)))
      }
    }

  /** Goes back to the config file's settings, from the next song. */
  def reset: F[SettingsView] = pending.set(fromFile) *> view

  /** Takes up the pending settings, as a song starts, and returns them. */
  def startSong: F[LightShowConfig] = pending.get.flatTap(playing.set)
}

object LiveSettings {

  def make[F[_]: Concurrent](fromFile: LightShowConfig, areas: List[MusicArea] = Nil): F[LiveSettings[F]] =
    (Ref.of[F, LightShowConfig](fromFile), Ref.of[F, LightShowConfig](fromFile), Ref.of[F, List[MusicArea]](areas))
      .mapN(new LiveSettings(fromFile, _, _, _))

  /**
   * Why `next` can't be used: anything the config file would reject, an area that isn't among `areas` (when they're
   * known), and any change to what needs a restart.
   */
  def problems(fromFile: LightShowConfig, next: LightShowConfig, areas: List[MusicArea] = Nil): List[String] =
    LightShowConfig.problems(next) ++ List(
      Option.when(areas.nonEmpty && next.area.exists(name => !areas.exists(_.name.equalsIgnoreCase(name.trim))))(
        s"area must be one of ${areas.map(a => s"\"${a.name}\"").mkString(", ")}"
      ),
      Option.when(next.beatSource != fromFile.beatSource)("beat-source only changes when the app restarts"),
      Option.when(next.audio.device != fromFile.audio.device)("audio.device only changes when the app restarts"),
      Option.when(next.frameIntervalMs != fromFile.frameIntervalMs)("frame-interval-ms only changes when the app restarts")
    ).flatten
}
