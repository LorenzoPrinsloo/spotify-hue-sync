package beat

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import scala.concurrent.duration.*

class BeatTrackerSpec extends ScalaCheckSuite {

  // Each case tracks seconds of generated audio, so run a fixed, repeatable sample of them.
  override def scalaCheckTestParameters = super.scalaCheckTestParameters.withMinSuccessfulTests(12)
  override val scalaCheckInitialSeed: String = org.scalacheck.rng.Seed(20261014L).toBase64

  test("the FFT puts a pure tone in the right frequency bin") {
    val n = 1024
    val signal = Array.tabulate(n)(i => math.sin(2 * math.Pi * 37 * i / n))
    val spectrum = Fft.magnitudes(signal)
    assertEquals(spectrum.indices.maxBy(spectrum), 37)
  }

  test("onset strength spikes at a hit and stays low during a steady tone") {
    val hop = 512
    val tone = Array.tabulate(hop * 20)(i => (0.3 * math.sin(2 * math.Pi * 440 * i / 44100.0)).toFloat)
    val (afterTone, steady) = tone.grouped(hop).foldLeft((OnsetDetector.initial(1024), Vector.empty[Double])) {
      case ((d, acc), h) => val (next, onset) = d.step(h); (next, acc :+ onset.flux)
    }
    val (audio, _) = Synth.drums(0.5, _ => 120, firstBeat = 0.1, hats = false)
    val hitFlux = audio.grouped(hop).foldLeft((afterTone, 0.0)) { case ((d, peak), h) =>
      val (next, onset) = d.step(h); (next, math.max(peak, onset.flux))
    }._2
    assert(steady.drop(4).max < hitFlux / 10, s"steady ${steady.drop(4).max}, hit $hitFlux")
  }

  property("finds the exact tempo of a drum pattern between 90 and 150 BPM, to within 1.5 BPM") {
    forAllNoShrink(Gen.choose(90.0, 150.0), Gen.choose(0.0, 0.5), Gen.choose(1L, 1000L)) { (bpm, first, seed) =>
      val (audio, _) = Synth.drums(10, _ => bpm, firstBeat = first, seed = seed)
      Synth.track(audio).beat.exists(b => math.abs(b.bpm - bpm) < 1.5)
    }
  }

  property("for very slow or fast patterns, finds the tempo or exactly double or half of it, never in between") {
    forAllNoShrink(Gen.oneOf(Gen.choose(72.0, 90.0), Gen.choose(150.0, 170.0)), Gen.choose(0.0, 0.5), Gen.choose(1L, 1000L)) { (bpm, first, seed) =>
      val (audio, _) = Synth.drums(10, _ => bpm, firstBeat = first, seed = seed)
      Synth.track(audio).beat.exists(b => Seq(bpm, 2 * bpm, bpm / 2).exists(t => math.abs(b.bpm - t) < 1.5))
    }
  }

  property("lines the beat grid up with the actual beats to within 30 ms") {
    forAllNoShrink(Gen.choose(72.0, 170.0), Gen.choose(0.0, 0.5), Gen.choose(1L, 1000L)) { (bpm, first, seed) =>
      val (audio, beats) = Synth.drums(10, _ => bpm, firstBeat = first, seed = seed)
      Synth.track(audio).beat.exists(b => Synth.phaseErrorMs(b, beats) < 30)
    }
  }

  test("isn't fooled into double time by off-beat hi-hats") {
    val (audio, _) = Synth.drums(10, _ => 95, hats = true)
    assertEqualsDouble(Synth.track(audio).beat.get.bpm, 95.0, 1.5)
  }

  test("follows a change of tempo") {
    val (audio, _) = Synth.drums(22, t => if (t < 10) 100 else 128)
    assertEqualsDouble(Synth.track(audio).beat.get.bpm, 128.0, 1.5)
  }

  test("reports no beat in silence") {
    assertEquals(Synth.track(Synth.silence(10)).beat, None)
  }

  test("needs a few seconds of audio before it commits to a tempo") {
    val (audio, _) = Synth.drums(2, _ => 120)
    assertEquals(Synth.track(audio).beat, None)
  }

  test("beatsAt counts whole beats on the beat and fractions between them") {
    val beat = BeatState(120, 10.seconds, 10.seconds)
    assertEqualsDouble(beat.beatsAt(10.seconds), 0.0, 1e-9)
    assertEqualsDouble(beat.beatsAt(11.seconds), 2.0, 1e-9)
    assertEqualsDouble(beat.beatsAt(10250.millis), 0.5, 1e-9)
    assertEqualsDouble(beat.beatsAt(9750.millis), -0.5, 1e-9)
  }

  // -- holding the beat ----------------------------------------------------

  test("holds the beat through a short silence") {
    val beat = Synth.track(Synth.concat(Synth.drums(10, _ => 128)._1, Synth.silence(4))).beat
    assertEqualsDouble(beat.map(_.bpm).getOrElse(0.0), 128.0, 1.5)
  }

  test("lets go of the beat once a silence outlasts the hold") {
    assertEquals(Synth.track(Synth.concat(Synth.drums(10, _ => 128)._1, Synth.silence(13))).beat, None)
  }

  test("with no hold, lets go as soon as the beat is unclear") {
    val settings = Synth.settings.copy(holdSeconds = 0)
    val tracked = Synth.frames(Synth.concat(Synth.drums(10, _ => 128)._1, Synth.silence(3))).foldLeft(BeatTracker.initial(settings))(_.step(_))
    assertEquals(tracked.beat, None)
  }

  test("holds the tempo through a passage with no clear beat, rather than guessing a new one") {
    val readings = Synth.readings(Synth.concat(Synth.drums(10, _ => 128)._1, Synth.pad(8)))
    val duringPad = readings.collect { case (t, r) if t > 11 => r.beat.map(_.bpm) }
    assert(duringPad.forall(_.exists(bpm => math.abs(bpm - 128) < 1.5)), duringPad.distinct.toString)
  }

  private val weakBeat = Synth.mix(Synth.drums(16, _ => 124, loudness = 0.15)._1, Synth.noise(16, loudness = 0.2))

  test("once locked, keeps the beat through a long passage where it's weaker but steady") {
    val beat = Synth.track(Synth.concat(Synth.drums(10, _ => 124)._1, weakBeat)).beat
    assertEqualsDouble(beat.map(_.bpm).getOrElse(0.0), 124.0, 1.5)
  }

  test("that passage would lose the beat if weaker estimates couldn't keep it") {
    val strict = Synth.settings.copy(keepConfidence = Synth.settings.minConfidence)
    val tracked = Synth.frames(Synth.concat(Synth.drums(10, _ => 124)._1, weakBeat)).foldLeft(BeatTracker.initial(strict))(_.step(_))
    assertEquals(tracked.beat, None)
  }

  test("a weak beat on its own isn't enough to lock on") {
    assertEquals(Synth.track(weakBeat).beat, None)
  }

  test("noise alone never produces a beat") {
    assertEquals(Synth.track(Synth.noise(12)).beat, None)
  }

  // -- energy and treble ---------------------------------------------------

  test("energy rises when the music gets louder") {
    val readings = Synth.readings(Synth.concat(Synth.drums(12, _ => 120, loudness = 0.3)._1, Synth.drums(6, _ => 120)._1))
    assertEqualsDouble(Synth.at(readings, 11.5).energy, 1.0, 0.25)
    assert(Synth.at(readings, 13.5).energy > 1.8, Synth.at(readings, 13.5).toString)
  }

  test("energy falls when the music gets quieter") {
    val readings = Synth.readings(Synth.concat(Synth.drums(12, _ => 120)._1, Synth.drums(6, _ => 120, loudness = 0.3)._1))
    assert(Synth.at(readings, 14.5).energy < 0.5, Synth.at(readings, 14.5).toString)
  }

  test("energy settles at about 1 while the music stays at one level") {
    val readings = Synth.readings(Synth.drums(30, _ => 120)._1)
    val late = readings.collect { case (t, r) if t > 10 => r.energy }
    assert(late.forall(e => e > 0.7 && e < 1.4), s"${late.min} to ${late.max}")
  }

  test("treble rises when hi-hats come in") {
    val readings = Synth.readings(Synth.concat(Synth.drums(12, _ => 120, hats = false)._1, Synth.drums(6, _ => 120)._1))
    assert(Synth.at(readings, 11.5).treble < 1.2, Synth.at(readings, 11.5).toString)
    assert(Synth.at(readings, 14.0).treble > 1.3, Synth.at(readings, 14.0).toString)
  }

  test("a hi-hat's onset is treble-led, a kick's isn't") {
    def strongest(audio: Array[Float]): Onset =
      audio.grouped(512).foldLeft((OnsetDetector.initial(1024), Vector.empty[Onset])) { case ((d, acc), h) =>
        val (next, onset) = d.step(h); (next, acc :+ onset)
      }._2.drop(4).maxBy(o => o.flux + o.trebleFlux)
    val kick = strongest(Synth.drums(1, _ => 120, hats = false)._1)
    val hat = strongest(Synth.drums(1, _ => 120, kicks = false)._1)
    assert(kick.trebleFlux < BeatTracker.TrebleLed * kick.flux, kick.toString)
    assert(hat.trebleFlux > BeatTracker.TrebleLed * hat.flux, hat.toString)
  }

  // -- big hits and sparkles -----------------------------------------------

  test("a crash is a big hit, at the moment it happens, even on top of a kick") {
    (1L to 4L).foreach { seed =>
      val (drums, _) = Synth.drums(20, _ => 100 + seed * 10.0, seed = seed)
      val hits = Synth.hitTimes(Synth.readings(Synth.withCrash(drums, 14.0)))
      assert(hits.size == 1 && math.abs(hits.head - 14.0) < 0.06, s"seed $seed: $hits")
    }
  }

  test("a steady drum pattern has no big hits") {
    (1L to 4L).foreach { seed =>
      val hits = Synth.hitTimes(Synth.readings(Synth.drums(20, _ => 100 + seed * 10.0, seed = seed)._1))
      assertEquals(hits, Vector.empty, s"seed $seed")
    }
  }

  test("a jump from quiet to loud is a big hit") {
    val hits = Synth.hitTimes(Synth.readings(Synth.concat(Synth.drums(12, _ => 120, loudness = 0.3)._1, Synth.drums(6, _ => 120)._1)))
    assert(hits.exists(t => t >= 12.0 && t < 12.6), hits.toString)
  }

  test("hi-hats sparkle, kicks hardly ever do") {
    val withHats = Synth.readings(Synth.drums(20, _ => 120)._1).last._2.sparkles
    val kicksOnly = Synth.readings(Synth.drums(20, _ => 120, hats = false)._1).last._2.sparkles
    assert(withHats >= 20, s"$withHats sparkles from 40 hi-hats")
    assert(kicksOnly <= 12, s"$kicksOnly sparkles from 40 kicks")
  }

  test("nothing counts as a big hit or sparkle until a few seconds of music have set the bar") {
    val reading = Synth.readings(Synth.withCrash(Synth.drums(3, _ => 120)._1, 1.5)).last._2
    assertEquals((reading.hit, reading.sparkles), (None, 0L))
  }
}
