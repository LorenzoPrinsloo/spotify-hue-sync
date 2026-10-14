package domain.engine

import domain.config.PauseSettings
import domain.hue.{Frame, LightUpdate, Zone}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import scala.concurrent.duration.*
import testkit.Colours.{close, rgb}

class RendererSpec extends ScalaCheckSuite {

  private val zone = Zone("00000000-0000-0000-0000-000000000000", List(0, 1, 2, 3))
  private val resting = RGB.safe(30, 20, 8).get
  private val pause = PauseSettings(resting, fadeOutMs = 2000)

  private val playedFrame: Gen[Frame] =
    Gen.listOfN(zone.channels.size, rgb).map(cs => Frame(zone.id, zone.channels.zip(cs).map(LightUpdate.apply.tupled)))

  private def distance(f: Frame): Int =
    f.updates.map(u => u.color.toVector.zip(resting.toVector).map((a, b) => math.abs(a - b)).sum).sum

  test("before anything has played, every channel shows the resting colour") {
    val (_, frame) = Renderer.paused(Renderer.initial, 5.seconds, zone, pause)
    assertEquals(frame, Renderer.restingFrame(zone, resting))
  }

  property("pausing starts from the last frame that played") {
    forAll(playedFrame) { played =>
      val (state, _) = Renderer.playing(played)
      Renderer.paused(state, 10.seconds, zone, pause)._2 == played
    }
  }

  property("once the fade-out has passed, every channel shows the resting colour") {
    forAll(playedFrame, Gen.choose(2000L, 60000L)) { (played, afterMs) =>
      val (state, _) = Renderer.playing(played)
      val (paused, _) = Renderer.paused(state, 10.seconds, zone, pause)
      Renderer.paused(paused, 10.seconds + afterMs.millis, zone, pause)._2.updates.forall(_.color == resting)
    }
  }

  property("half way through the fade, each channel is half way to the resting colour") {
    forAll(playedFrame) { played =>
      val (state, _) = Renderer.playing(played)
      val (paused, _) = Renderer.paused(state, 10.seconds, zone, pause)
      val half = Renderer.paused(paused, 11.seconds, zone, pause)._2
      half.updates.zip(played.updates).forall((h, p) => close(h.color, p.color.mix(resting, 0.5)))
    }
  }

  property("the lights only ever move towards the resting colour while paused") {
    forAll(playedFrame, Gen.listOfN(20, Gen.choose(0L, 300L))) { (played, steps) =>
      val (start, _) = Renderer.playing(played)
      val times = steps.scanLeft(0L)(_ + _).map(ms => 10.seconds + ms.millis)
      val distances = times.foldLeft((start, List.empty[Int])) { case ((state, acc), now) =>
        val (next, frame) = Renderer.paused(state, now, zone, pause)
        (next, distance(frame) :: acc)
      }._2.reverse
      distances.zip(distances.drop(1)).forall((a, b) => b <= a)
    }
  }

  property("the fade keeps the zone and channel ids") {
    forAll(playedFrame, Gen.choose(0.0, 1.0)) { (played, t) =>
      val faded = Renderer.fade(played, resting, t)
      faded.zoneId == played.zoneId && faded.updates.map(_.id) == played.updates.map(_.id)
    }
  }

  test("pausing again after a resume restarts the fade from the new frame") {
    val first = Frame(zone.id, zone.channels.map(LightUpdate(_, RGB.safe(255, 0, 0).get)))
    val second = Frame(zone.id, zone.channels.map(LightUpdate(_, RGB.safe(0, 0, 255).get)))
    val (s1, _) = Renderer.playing(first)
    val (s2, _) = Renderer.paused(s1, 1.second, zone, pause)
    val (s3, _) = Renderer.paused(s2, 4.seconds, zone, pause)
    val (s4, _) = Renderer.playing(second)
    assertEquals(s4.pausedSince, None)
    assertEquals(Renderer.paused(s4, 10.seconds, zone, pause)._2, second)
    assert(s3.pausedSince.contains(1.second))
  }

  test("a zero fade-out switches straight to the resting colour") {
    val played = Frame(zone.id, zone.channels.map(LightUpdate(_, RGB.safe(255, 255, 255).get)))
    val (state, _) = Renderer.playing(played)
    val frame = Renderer.paused(state, 1.second, zone, pause.copy(fadeOutMs = 0))._2
    assertEquals(frame, Renderer.restingFrame(zone, resting))
  }
}
