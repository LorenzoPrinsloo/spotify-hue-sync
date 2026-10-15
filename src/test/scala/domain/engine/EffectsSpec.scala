package domain.engine

import beat.{AudioReading, BeatState, Groove, Hit}
import domain.config.ReactiveSettings
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import scala.concurrent.duration.*

class EffectsSpec extends ScalaCheckSuite {

  private val settings = ReactiveSettings()
  private val now = 100.seconds

  private def effects(reading: AudioReading, lights: Int = 6, settings: ReactiveSettings = settings): Effects =
    Effects.at(now, reading, lights, settings)

  test("a typical moment changes nothing but which job each light has") {
    val typical = effects(AudioReading(None))
    assertEquals(typical.copy(roles = Vector.empty), Effects.neutral)
    assertEquals(typical.roles, Vector.fill(3)(Vector(LightRole.Rhythm, LightRole.Colour)).flatten)
    assertEquals(typical.swell, settings.colourSwell)
  }

  private val rolePatterns: Gen[List[LightRole]] =
    Gen.choose(1, 4).flatMap(n => Gen.listOfN(n, Gen.oneOf(LightRole.values.toList)))

  property("lights take their roles from the pattern, repeated across the zone in channel order") {
    forAll(rolePatterns, Gen.choose(0, 12)) { (pattern, lights) =>
      val roles = effects(AudioReading(None), lights, settings.copy(roles = pattern)).roles
      roles.size == lights && roles.indices.forall(i => roles(i) == pattern(i % pattern.size))
    }
  }

  property("colour lights move along the palette with the pitch, as far as the reach at the top or bottom") {
    forAll(Gen.choose(-1.0, 1.0), Gen.choose(0.0, 3.0)) { (pitch, reach) =>
      math.abs(effects(AudioReading(None, pitch = pitch), 6, settings.copy(colourReach = reach)).paletteShift - pitch * reach) < 1e-12
    }
  }

  test("the kick pattern plays along the beat grid, and only while there's a beat") {
    val beat = BeatState(124, now - 3.seconds, now, number = 40)
    val groove = Groove.Prior
    assertEquals(effects(AudioReading(None, groove = groove)).rhythm, None)
    assertEquals(effects(AudioReading(Some(beat))).rhythm, None)
    assertEquals(effects(AudioReading(Some(beat), groove = groove)).rhythm, Some(Rhythm(groove, beat.countAt(now))))
  }

  property("brightness follows the energy, kept between the quietest and loudest settings") {
    forAll(Gen.choose(0.0, 5.0)) { energy =>
      effects(AudioReading(None, energy = energy)).intensity == math.max(settings.quietest, math.min(settings.loudest, energy))
    }
  }

  property("the palette turns with the treble, up to the colour shift either way") {
    forAll(Gen.choose(0.0, 4.0)) { treble =>
      val expected = math.round(settings.colourShift * math.max(-1.0, math.min(1.0, treble - 1))).toInt
      val shift = effects(AudioReading(None, treble = treble)).hueShift
      shift == expected && math.abs(shift) <= settings.colourShift
    }
  }

  test("a big hit flashes at full strength at once, then fades") {
    val hit = Some(Hit(now, 1.0))
    assertEqualsDouble(effects(AudioReading(None, hit = hit)).flash, settings.flash, 1e-9)
    val later = Effects.at(now + settings.flashDecayMs.millis, AudioReading(None, hit = hit), 6, settings)
    assertEqualsDouble(later.flash, settings.flash * math.exp(-1), 1e-6)
    assertEquals(Effects.at(now + 3.seconds, AudioReading(None, hit = hit), 6, settings).flash, 0.0)
  }

  property("a weaker hit flashes less") {
    forAll(Gen.choose(0.05, 1.0)) { strength =>
      math.abs(effects(AudioReading(None, hit = Some(Hit(now, strength)))).flash - settings.flash * strength) < 1e-9
    }
  }

  property("a sparkle twinkles exactly one light, which exists and isn't a rhythm light, and fades") {
    forAll(rolePatterns, Gen.choose(1, 12), Gen.choose(0L, 10000L)) { (pattern, lights, count) =>
      val reactive = settings.copy(roles = pattern)
      val reading = AudioReading(None, sparkle = Some(Hit(now, 1.0)), sparkles = count)
      val roles = LightRole.assign(pattern, lights)
      val sparkle = Effects.at(now, reading, lights, reactive).sparkle
      val faded = Effects.at(now + 2.seconds, reading, lights, reactive).sparkle
      val twinkles =
        sparkle.exists(s => s.light >= 0 && s.light < lights && roles(s.light) != LightRole.Rhythm && math.abs(s.amount - settings.sparkle) < 1e-9)
      faded.isEmpty && (if (roles.forall(_ == LightRole.Rhythm)) sparkle.isEmpty else twinkles)
    }
  }

  test("successive sparkles land on different lights") {
    val lights = (1L to 12L).map(n => effects(AudioReading(None, sparkle = Some(Hit(now, 1.0)), sparkles = n)).sparkle.get.light)
    assert(lights.zip(lights.tail).forall(_ != _), lights.toString)
  }

  property("a light is never pushed past white") {
    forAll(Gen.choose(0.0, 1.0), Gen.choose(0.0, 1.0), Gen.choose(0, 5)) { (flash, sparkle, light) =>
      val white = Effects(flash = flash, sparkle = Some(Sparkle(light, sparkle))).whiteFor(light)
      white >= 0 && white <= 1 && white >= flash
    }
  }
}
