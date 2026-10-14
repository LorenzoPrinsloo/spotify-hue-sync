import domain.engine.{Palette, RGB}
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll

class SyncEngineSpec extends ScalaCheckSuite {

  private val zoneId = "00000000-0000-0000-0000-000000000000"

  private val rgb: Gen[RGB] = for {
    r <- Gen.choose(0, 255)
    g <- Gen.choose(0, 255)
    b <- Gen.choose(0, 255)
  } yield RGB.safe(r, g, b).get

  given Arbitrary[Palette] = Arbitrary(Gen.choose(1, 6).flatMap(n => Gen.listOfN(n, rgb)).map(cs => Palette(cs.toVector)))

  private def channels(c: RGB): Vector[Int] = c.toVector

  property("every frame drives all seven lights with in-range colour values") {
    forAll(Gen.choose(10.0, 240.0), Gen.choose(0L, 10000000L), summon[Arbitrary[Palette]].arbitrary) { (bpm, progressMs, palette) =>
      val frame = SyncEngine.pulseFrame(zoneId, bpm, progressMs, palette)
      frame.updates.map(_.id) == (0 until 7).toList &&
        frame.updates.flatMap(u => channels(u.color)).forall(v => v >= 0 && v <= 255)
    }
  }

  property("the first light is at its dimmest at the start of a beat") {
    forAll { (c: RGB) =>
      val frame = SyncEngine.pulseFrame(zoneId, 60.0, 0L, Palette(Vector(c)))
      channels(frame.updates.head.color) == channels(c).map(v => (v * 0.15).toInt)
    }
  }

  property("the first light is at full brightness at the peak of the pulse") {
    forAll { (c: RGB) =>
      val frame = SyncEngine.pulseFrame(zoneId, 60.0, 250L, Palette(Vector(c)))
      channels(frame.updates.head.color) == channels(c).map(v => math.min(255, (v * 2.65).toInt))
    }
  }

  property("each light follows the previous one 0.15 of a beat later") {
    forAll(rgb, Gen.choose(1000L, 100000L), Gen.choose(1, 6)) { (c, progressMs, k) =>
      val palette = Palette(Vector(c))
      val now = SyncEngine.pulseFrame(zoneId, 60.0, progressMs, palette)
      val earlier = SyncEngine.pulseFrame(zoneId, 60.0, progressMs - 150L * k, palette)
      channels(now.updates(k).color).zip(channels(earlier.updates.head.color)).forall((a, b) => math.abs(a - b) <= 1)
    }
  }

  given Arbitrary[RGB] = Arbitrary(rgb)
}
