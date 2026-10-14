package domain.engine

import domain.config.PauseSettings
import domain.hue.{Frame, LightUpdate, Zone}
import scala.concurrent.duration.FiniteDuration

/** What the renderer remembers between frames: the last frame it played, and when playback stopped. */
case class RenderState(lastPlayed: Option[Frame], pausedSince: Option[FiniteDuration])

object Renderer {

  val initial: RenderState = RenderState(None, None)

  def playing(frame: Frame): (RenderState, Frame) =
    (RenderState(Some(frame), None), frame)

  /** While nothing plays, fade from the last played frame to the resting colour over `fadeOutMs`, then hold it. */
  def paused(state: RenderState, now: FiniteDuration, zone: Zone, pause: PauseSettings): (RenderState, Frame) = {
    val since = state.pausedSince.getOrElse(now)
    val from = state.lastPlayed.getOrElse(restingFrame(zone, pause.restingColour))
    val progress =
      if (pause.fadeOutMs <= 0) 1.0
      else math.min(1.0, (now - since).toMillis.toDouble / pause.fadeOutMs)
    (state.copy(pausedSince = Some(since)), fade(from, pause.restingColour, progress))
  }

  def fade(from: Frame, to: RGB, progress: Double): Frame =
    from.copy(updates = from.updates.map(u => u.copy(color = u.color.mix(to, progress))))

  def restingFrame(zone: Zone, colour: RGB): Frame =
    Frame(zone.id, zone.channels.map(LightUpdate(_, colour)))
}
