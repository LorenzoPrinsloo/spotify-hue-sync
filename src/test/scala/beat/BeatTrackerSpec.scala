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
      case ((d, acc), h) => val (next, flux, _) = d.step(h); (next, acc :+ flux)
    }
    val (audio, _) = Synth.drums(0.5, _ => 120, firstBeat = 0.1, hats = false)
    val hitFlux = audio.grouped(hop).foldLeft((afterTone, 0.0)) { case ((d, peak), h) =>
      val (next, flux, _) = d.step(h); (next, math.max(peak, flux))
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

  test("drops the beat when the music stops") {
    val (audio, _) = Synth.drums(10, _ => 120)
    val playing = Synth.track(audio)
    assert(playing.beat.isDefined)
    val afterSilence = Synth.frames(Synth.silence(4), latency = 10.seconds).foldLeft(playing)(_.step(_))
    assertEquals(afterSilence.beat, None)
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
}
