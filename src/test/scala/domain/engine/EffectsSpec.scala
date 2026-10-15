package domain.engine

import beat.{AudioReading, Hit}
import domain.config.ReactiveSettings
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import scala.concurrent.duration.*

class EffectsSpec extends ScalaCheckSuite {

  private val settings = ReactiveSettings()
  private val now = 100.seconds

  private def effects(reading: AudioReading, lights: Int = 6): Effects = Effects.at(now, reading, lights, settings)

  test("a typical moment changes nothing") {
    assertEquals(effects(AudioReading(None)), Effects.neutral)
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

  property("a sparkle twinkles exactly one light, which exists, and fades") {
    forAll(Gen.choose(1, 12), Gen.choose(0L, 10000L)) { (lights, count) =>
      val reading = AudioReading(None, sparkle = Some(Hit(now, 1.0)), sparkles = count)
      val sparkle = Effects.at(now, reading, lights, settings).sparkle
      val faded = Effects.at(now + 2.seconds, reading, lights, settings).sparkle
      sparkle.exists(s => s.light >= 0 && s.light < lights && math.abs(s.amount - settings.sparkle) < 1e-9) && faded.isEmpty
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
