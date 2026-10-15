package client

import cats.effect.{Concurrent, Resource}
import cats.effect.std.Hotswap
import cats.syntax.all.*
import domain.hue.Frame
import fs2.Stream

/**
 * Streams frames to whichever entertainment area each is for. The first frame for an area opens a session on it; a
 * frame for another area first closes that session, so the old area's lights go back to how they were, then opens one
 * on the new area. The last session closes when the frames end.
 */
object EntertainmentStream {

  def apply[F[_]: Concurrent, C](session: String => Resource[F, C], send: (C, Frame) => F[Unit])(frames: Stream[F, Frame]): Stream[F, Nothing] =
    Stream.resource(Hotswap.create[F, C]).flatMap { hotswap =>
      frames
        .evalMapAccumulate(Option.empty[(String, C)]) { (open, frame) =>
          open.filter((area, _) => area == frame.zoneId) match {
            case Some(current @ (_, connection)) => send(connection, frame).as((Some(current), ()))
            case None =>
              for {
                _ <- hotswap.clear
                connection <- hotswap.swap(session(frame.zoneId))
                _ <- send(connection, frame)
              } yield (Some(frame.zoneId -> connection), ())
          }
        }
        .drain
    }

  /** Frames to the bridge: each session starts streaming on its area, then connects over DTLS. */
  def toBridge[F[_]: Concurrent](hue: HueClient[F], emitter: DtlsEmitter[F])(frames: Stream[F, Frame]): Stream[F, Nothing] =
    apply[F, org.eclipse.californium.scandium.DTLSConnector](
      area => Resource.make(hue.setStreaming(area, active = true))(_ => hue.setStreaming(area, active = false)) >> emitter.resource,
      emitter.send
    )(frames)
}
