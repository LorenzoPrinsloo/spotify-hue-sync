package audio

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import scala.concurrent.duration.*

class CaptureClockSpec extends ScalaCheckSuite {

  private val rate = 44100.0
  private val hop = 512
  private val hopNanos = hop / rate * 1e9
  private val latency = 7.millis

  /** When the audio of read i (counting from 1) finished arriving, with the device's latency. */
  private def arrived(i: Int): Long = (i * hopNanos).toLong + latency.toNanos

  private def stamps(clock: CaptureClock, readsAt: Seq[Long]): Seq[Long] =
    readsAt.scanLeft((clock, 0L)) { case ((c, _), at) => c.next(hop, at.nanos) match { case (n, t) => (n, t.toNanos) } }.drop(1).map(_._2)

  property("reads that return late, in bursts, are stamped with when their audio arrived") {
    val delays = Gen.listOfN(400, Gen.frequency(1 -> Gen.const(0L), 4 -> Gen.choose(0L, 40.millis.toNanos)))
    forAll(delays) { late =>
      val readsAt = late.zipWithIndex.map((d, i) => arrived(i + 1) + d)
      val clock = CaptureClock(rate, window = 1000)
      val promptSoFar = late.scanLeft(false)(_ || _ == 0L).drop(1)
      stamps(clock, readsAt).zipWithIndex.zip(promptSoFar).forall { case ((stamp, i), prompt) =>
        !prompt || math.abs(stamp - arrived(i + 1)) < 1000
      }
    }
  }

  test("a stamp is never later than the read that produced it") {
    val readsAt = (1 to 200).map(i => arrived(i) + (if (i % 7 == 0) 0L else 25.millis.toNanos))
    stamps(CaptureClock(rate, window = 50), readsAt).zip(readsAt).foreach((stamp, at) => assert(stamp <= at))
  }

  test("if the audio falls behind for good, the clock follows once the window has passed") {
    val shift = 30.millis.toNanos
    val readsAt = (1 to 300).map(i => arrived(i) + (if (i > 100) shift else 0L))
    val stamped = stamps(CaptureClock(rate, window = 50), readsAt)
    assert(math.abs(stamped(120) - arrived(121)) < 1000, "within the window, the earlier anchor still holds")
    assert(math.abs(stamped(200) - (arrived(201) + shift)) < 1000, "after the window, the new lag is the anchor")
  }
}
