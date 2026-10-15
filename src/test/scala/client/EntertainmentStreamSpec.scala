package client

import cats.effect.{IO, Ref, Resource}
import domain.hue.Frame
import fs2.Stream
import munit.CatsEffectSuite

class EntertainmentStreamSpec extends CatsEffectSuite {

  /** Streams frames for these areas, in order, and returns what happened: sessions opening and closing, frames sent. */
  private def stream(areas: String*): IO[List[String]] =
    Ref.of[IO, List[String]](Nil).flatMap { log =>
      def note(event: String) = log.update(_ :+ event)
      val session = (area: String) => Resource.make(note(s"open $area").as(area))(_ => note(s"close $area"))
      Stream.emits(areas.map(Frame(_, Nil)))
        .through(EntertainmentStream[IO, String](session, (open, frame) => note(s"send ${frame.zoneId} on $open")))
        .compile
        .drain *> log.get
    }

  test("frames for one area share one session, closed when the frames end") {
    stream("up", "up", "up").assertEquals(List("open up", "send up on up", "send up on up", "send up on up", "close up"))
  }

  test("a frame for another area closes the old area's session before opening the new one") {
    stream("up", "down", "down", "up").assertEquals(
      List("open up", "send up on up", "close up", "open down", "send down on down", "send down on down", "close down", "open up", "send up on up", "close up")
    )
  }
}
