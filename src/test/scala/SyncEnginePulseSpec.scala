import domain.config.PulseSettings
import domain.engine.{Palette, RGB}
import domain.hue.Zone
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import testkit.Colours.*

class SyncEnginePulseSpec extends ScalaCheckSuite {

  private val zoneId = "00000000-0000-0000-0000-000000000000"
  private val settings = PulseSettings()
  private val flat = settings.copy(restLevel = 1.0, peakBoost = 0.0)
  private val sevenLights = Zone(zoneId, (0 until 7).toList)

  private val channels: Gen[List[Int]] =
    Gen.choose(1, 12).flatMap(n => Gen.pick(n, 0 to 30)).map(_.toList.sorted)

  private val palettes: Gen[Palette] =
    Gen.choose(1, 6).flatMap(n => Gen.listOfN(n, rgb)).map(cs => Palette(cs.toVector))

  private val position: Gen[Long] = Gen.choose(0L, 10000000L)

  property("there is one update per channel in the zone, using the bridge's channel ids") {
    forAll(channels, palettes, Gen.choose(30.0, 200.0), position) { (ids, palette, bpm, pos) =>
      SyncEngine.pulseFrame(Zone(zoneId, ids), palette, bpm, pos, settings).updates.map(_.id) == ids
    }
  }

  property("light i takes palette colour i, starting from the first colour") {
    forAll(channels, palettes, position) { (ids, palette, pos) =>
      SyncEngine.pulseFrame(Zone(zoneId, ids), palette, 60, pos, flat).updates.zipWithIndex
        .forall((u, i) => close(u.color, palette.getColor(i)))
    }
  }

  property("at the peak the hue is unchanged, so bright colours don't wash out") {
    forAll(vivid) { c =>
      val lit = SyncEngine.pulseFrame(Zone(zoneId, List(0)), Palette(Vector(c)), 60, 250L, settings).updates.head.color
      degrees(hueDistance(hue(lit), hue(c))) < 2 &&
        math.abs(saturation(lit) - saturation(c)) < 0.02 &&
        math.abs(brightness(lit) - math.min(1.0, brightness(c) * 2.65)) <= 1.0 / 255 + 1e-6
    }
  }

  property("at the start of a beat a light rests at 15% of its brightness") {
    forAll(rgb) { c =>
      val rest = SyncEngine.pulseFrame(Zone(zoneId, List(0)), Palette(Vector(c)), 60, 0L, settings).updates.head.color
      math.abs(brightness(rest) - brightness(c) * 0.15) <= 1.0 / 255 + 1e-6
    }
  }

  property("each light follows the previous one by the configured stagger") {
    forAll(rgb, Gen.choose(2000L, 100000L), Gen.choose(1, 6), Gen.choose(0.05, 0.3)) { (c, pos, k, stagger) =>
      val s = settings.copy(stagger = stagger)
      val palette = Palette(Vector(c))
      val now = SyncEngine.pulseFrame(sevenLights, palette, 60, pos, s)
      val earlier = SyncEngine.pulseFrame(sevenLights, palette, 60, pos - math.round(1000 * stagger * k), s)
      close(now.updates(k).color, earlier.updates.head.color, 2)
    }
  }

  property("the pulse repeats every beat at the given tempo") {
    forAll(Gen.oneOf(30, 60, 75, 100, 120, 150), palettes, position) { (bpm, palette, pos) =>
      val beatMs = 60000L / bpm
      val a = SyncEngine.pulseFrame(sevenLights, palette, bpm, pos, settings)
      val b = SyncEngine.pulseFrame(sevenLights, palette, bpm, pos + beatMs, settings)
      a.updates.zip(b.updates).forall((x, y) => close(x.color, y.color))
    }
  }

  property("doubling the tempo doubles the speed of the pulse") {
    forAll(palettes, Gen.choose(0L, 1000000L)) { (palette, pos) =>
      val slow = SyncEngine.pulseFrame(sevenLights, palette, 60, 2 * pos, settings)
      val fast = SyncEngine.pulseFrame(sevenLights, palette, 120, pos, settings)
      slow.updates.zip(fast.updates).forall((x, y) => close(x.color, y.color))
    }
  }

  property("every value stays within 0-255 whatever the tuning") {
    forAll(pulseSettings, palettes, Gen.choose(10.0, 240.0), position) { (s, palette, bpm, pos) =>
      SyncEngine.pulseFrame(sevenLights, palette, bpm, pos, s).updates.forall(_.color.toVector.forall(v => v >= 0 && v <= 255))
    }
  }
}
