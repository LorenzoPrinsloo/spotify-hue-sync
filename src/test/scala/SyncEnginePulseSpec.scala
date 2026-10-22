import domain.config.{PulseSettings, SwellSettings}
import beat.Groove
import domain.engine.{Effects, LightRole, Palette, Pulse, RGB, Rhythm, Sparkle}
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

  private val pulses: Gen[Double] = Gen.choose(0.0, 100000.0)

  property("there is one update per channel in the zone, using the bridge's channel ids") {
    forAll(channels, palettes, pulses) { (ids, palette, p) =>
      SyncEngine.pulseFrame(Zone(zoneId, ids), palette, p, settings).updates.map(_.id) == ids
    }
  }

  property("light i takes palette colour i, starting from the first colour") {
    forAll(channels, palettes, pulses) { (ids, palette, p) =>
      SyncEngine.pulseFrame(Zone(zoneId, ids), palette, p, flat).updates.zipWithIndex
        .forall((u, i) => close(u.color, palette.getColor(i)))
    }
  }

  property("on the beat a light is at its peak, with the hue unchanged, so bright colours don't wash out") {
    forAll(vivid, Gen.choose(-1000, 1000)) { (c, beat) =>
      val lit = SyncEngine.pulseFrame(Zone(zoneId, List(0)), Palette(Vector(c)), beat.toDouble, settings).updates.head.color
      degrees(hueDistance(hue(lit), hue(c))) < 2 &&
      math.abs(saturation(lit) - saturation(c)) < 0.02 &&
      math.abs(brightness(lit) - math.min(1.0, brightness(c) * 2.65)) <= 1.0 / 255 + 1e-6
    }
  }

  property("just before it starts to rise for the next beat, a light rests at 15% of its brightness") {
    forAll(rgb) { c =>
      val rest = SyncEngine.pulseFrame(Zone(zoneId, List(0)), Palette(Vector(c)), 1.0 - settings.attack, settings).updates.head.color
      math.abs(brightness(rest) - brightness(c) * 0.15) <= 1.0 / 255 + 1e-6
    }
  }

  property("each light follows the previous one by the configured stagger") {
    forAll(rgb, Gen.choose(2.0, 100.0), Gen.choose(1, 6), Gen.choose(0.05, 0.3)) { (c, p, k, stagger) =>
      val s = settings.copy(stagger = stagger)
      val palette = Palette(Vector(c))
      val now = SyncEngine.pulseFrame(sevenLights, palette, p, s)
      val earlier = SyncEngine.pulseFrame(sevenLights, palette, p - stagger * k, s)
      close(now.updates(k).color, earlier.updates.head.color, 1)
    }
  }

  property("the pulse repeats every whole pulse") {
    forAll(palettes, pulses, Gen.choose(-5, 5)) { (palette, p, n) =>
      val a = SyncEngine.pulseFrame(sevenLights, palette, p, settings)
      val b = SyncEngine.pulseFrame(sevenLights, palette, p + n, settings)
      a.updates.zip(b.updates).forall((x, y) => close(x.color, y.color))
    }
  }

  property("pulsesAt counts pulses at the given tempo") {
    forAll(Gen.choose(0L, 10000000L), Gen.choose(10.0, 240.0)) { (pos, bpm) =>
      math.abs(SyncEngine.pulsesAt(pos, bpm) - pos / (60000.0 / bpm)) < 1e-6 &&
      math.abs(SyncEngine.pulsesAt(pos, 2 * bpm) - 2 * SyncEngine.pulsesAt(pos, bpm)) < 1e-6
    }
  }

  property("every value stays within 0-255 whatever the tuning") {
    forAll(pulseSettings, palettes, pulses) { (s, palette, p) =>
      SyncEngine.pulseFrame(sevenLights, palette, p, s).updates.forall(_.color.toVector.forall(v => v >= 0 && v <= 255))
    }
  }

  // -- effects --------------------------------------------------------------

  property("neutral effects leave the pulse exactly as it was") {
    forAll(channels, palettes, pulses) { (ids, palette, p) =>
      val zone = Zone(zoneId, ids)
      SyncEngine.pulseFrame(zone, palette, p, settings, Effects.neutral) == SyncEngine.pulseFrame(zone, palette, p, settings)
    }
  }

  property("intensity scales every light's brightness") {
    forAll(vivid, Gen.choose(0.0, 1.0), Gen.choose(0.3, 1.6)) { (c, p, intensity) =>
      val lit = SyncEngine.pulseFrame(Zone(zoneId, List(0)), Palette(Vector(c)), p, settings, Effects(intensity = intensity)).updates.head.color
      val expected = math.min(1.0, brightness(c) * Pulse.brightness(Pulse.onBeat(p, settings), settings) * intensity)
      math.abs(brightness(lit) - expected) <= 1.0 / 255 + 1e-6
    }
  }

  property("a full flash turns every light white") {
    forAll(channels, palettes, pulses) { (ids, palette, p) =>
      SyncEngine.pulseFrame(Zone(zoneId, ids), palette, p, settings, Effects(flash = 1.0)).updates.forall(_.color == RGB.safe(255, 255, 255).get)
    }
  }

  property("a flash only ever brightens") {
    forAll(channels, palettes, pulses, Gen.choose(0.0, 1.0)) { (ids, palette, p, flash) =>
      val zone = Zone(zoneId, ids)
      val plain = SyncEngine.pulseFrame(zone, palette, p, settings)
      val flashed = SyncEngine.pulseFrame(zone, palette, p, settings, Effects(flash = flash))
      plain.updates.zip(flashed.updates).forall((a, b) => a.color.toVector.zip(b.color.toVector).forall((x, y) => y >= x))
    }
  }

  property("a sparkle changes only its own light") {
    forAll(palettes, pulses, Gen.choose(0, 6)) { (palette, p, light) =>
      val plain = SyncEngine.pulseFrame(sevenLights, palette, p, settings)
      val sparkled = SyncEngine.pulseFrame(sevenLights, palette, p, settings, Effects(sparkle = Some(Sparkle(light, 0.8))))
      plain.updates.zip(sparkled.updates).zipWithIndex.forall { case ((a, b), i) => i == light || a == b }
    }
  }

  property("a rhythm light peaks on the sixteenths of the kick pattern, and rests between them") {
    forAll(rgb, Gen.choose(0, Groove.Steps - 1), Gen.choose(-50, 50)) { (c, step, bar) =>
      val groove = Vector.tabulate(Groove.Steps)(i => if (i == step) 1.0 else 0.0)
      val on = bar * Groove.BeatsPerBar + step.toDouble / Groove.StepsPerBeat
      def colourAt(beats: Double) =
        SyncEngine.pulseFrame(Zone(zoneId, List(0)), Palette(Vector(c)), 0.6, settings,
          Effects(roles = Vector(LightRole.Rhythm), rhythm = Some(Rhythm(groove, beats)))).updates.head.color
      colourAt(on) == c.scaleBrightness(Pulse.brightness(1, settings)) &&
      colourAt(on + Groove.BeatsPerBar / 2.0) == c.scaleBrightness(Pulse.brightness(0, settings))
    }
  }

  property("a rhythm light's brightness uses the rhythm intensity when there is one; pulse and colour lights don't") {
    forAll(palettes, pulses, Gen.choose(0.1, 1.0), Gen.choose(0.1, 1.0)) { (palette, p, intensity, rhythmIntensity) =>
      val roles = Vector(LightRole.Rhythm, LightRole.Pulse, LightRole.Colour)
      val zone = Zone(zoneId, List(0, 1, 2))
      val base = Effects(intensity = intensity, roles = roles)
      val plain = SyncEngine.pulseFrame(zone, palette, p, settings, base).updates
      val shifted = SyncEngine.pulseFrame(zone, palette, p, settings, base.copy(rhythmIntensity = Some(rhythmIntensity))).updates
      val level = Pulse.onBeat(p, settings)
      shifted(0).color == palette.getColor(0).scaleBrightness(Pulse.brightness(level, settings, rhythmIntensity)) &&
      plain.tail == shifted.tail
    }
  }

  property("without a beat, rhythm lights pulse together on every pulse rather than in a wave") {
    forAll(channels, palettes, pulses) { (ids, palette, p) =>
      val effects = Effects(roles = Vector.fill(ids.size)(LightRole.Rhythm))
      SyncEngine.pulseFrame(Zone(zoneId, ids), palette, p, settings, effects).updates.zipWithIndex.forall { (u, i) =>
        u.color == palette.getColor(i).scaleBrightness(Pulse.brightness(Pulse.onBeat(p, settings), settings))
      }
    }
  }

  private val swells: Gen[SwellSettings] = for {
    beats <- Gen.oneOf(1.0, 2.0, 4.0)
    shape <- pulseSettings
  } yield SwellSettings(beats, shape.attack, shape.sustain, shape.decayCurve, shape.restLevel, shape.peakBoost)

  private def colourAt(c: RGB, swell: SwellSettings, beats: Double, pulses: Double = 0.6): RGB = {
    val effects = Effects(roles = Vector(LightRole.Colour), rhythm = Some(Rhythm(Groove.Prior, beats)), swell = swell)
    SyncEngine.pulseFrame(Zone(zoneId, List(0)), Palette(Vector(c)), pulses, settings, effects).updates.head.color
  }

  property("a colour light swells once every few beats, at rest until it starts to rise and at its peak on the beat") {
    forAll(rgb, swells, Gen.choose(-500, 500)) { (c, swell, n) =>
      val peak = n * swell.beats
      colourAt(c, swell, peak) == c.scaleBrightness(Pulse.brightness(1, swell.shape)) &&
      colourAt(c, swell, peak - swell.attack * swell.beats) == c.scaleBrightness(Pulse.brightness(0, swell.shape))
    }
  }

  property("with a beat, the swell follows the beat count, whatever the pulse lights are doing") {
    forAll(rgb, swells, Gen.choose(-500.0, 500.0), pulses, pulses) { (c, swell, beats, p, q) =>
      colourAt(c, swell, beats, p) == colourAt(c, swell, beats, q)
    }
  }

  property("throughout the swell, colour lights keep their place along the palette") {
    forAll(channels, palettes, pulses, Gen.choose(-2.0, 2.0), swells) { (ids, palette, p, shift, swell) =>
      val effects = Effects(roles = Vector.fill(ids.size)(LightRole.Colour), paletteShift = shift, swell = swell)
      val level = Pulse.onBeat(p / swell.beats, swell.shape)
      SyncEngine.pulseFrame(Zone(zoneId, ids), palette, p, settings, effects).updates.zipWithIndex.forall { (u, i) =>
        u.color == palette.along(i, shift).scaleBrightness(Pulse.brightness(level, swell.shape))
      }
    }
  }

  property("pulse lights do the classic travelling pulse whatever the other lights do") {
    forAll(Gen.listOfN(7, Gen.oneOf(LightRole.values.toList)), palettes, pulses) { (roles, palette, p) =>
      val effects = Effects(roles = roles.toVector, rhythm = Some(Rhythm(Groove.Prior, p + 0.3)), paletteShift = 0.7)
      val mixed = SyncEngine.pulseFrame(sevenLights, palette, p, settings, effects).updates
      val classic = SyncEngine.pulseFrame(sevenLights, palette, p, settings).updates
      roles.indices.filter(roles(_) == LightRole.Pulse).forall(i => mixed(i) == classic(i))
    }
  }

  property("a hue shift turns every light's colour by that much") {
    forAll(vivid, Gen.choose(-25, 25)) { (c, shift) =>
      val frame = SyncEngine.pulseFrame(Zone(zoneId, List(0)), Palette(Vector(c)), 0.25, settings, Effects(hueShift = shift))
      val turned = frame.updates.head.color
      math.abs(degrees(hueDistance(hue(turned), hue(c))) - math.abs(shift)) < 2.5
    }
  }
}
