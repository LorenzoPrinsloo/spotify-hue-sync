package beat

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import scala.concurrent.duration.*

class RelatedTempoSpec extends ScalaCheckSuite {

  override def scalaCheckTestParameters = super.scalaCheckTestParameters.withMinSuccessfulTests(12)
  override val munitTimeout: Duration = 2.minutes
  override val scalaCheckInitialSeed: String = org.scalacheck.rng.Seed(20261014L).toBase64

  private def tempos(audio: Array[Float]): Vector[Double] =
    Synth.readings(audio).dropWhile(_._2.beat.isEmpty).flatMap(_._2.beat.map(_.bpm))

  private def withAccents(kick: Double, ratio: Double, loudness: Double, seed: Long): Array[Float] = {
    val (kicks, _) = Synth.drums(25, _ => kick, hats = false, seed = seed)
    Synth.mix(kicks, Synth.accents(25, _ => kick * ratio, loudness = loudness, seed = seed))
  }

  private def holdsTempo(audio: Array[Float], bpm: Double): Boolean = {
    val all = tempos(audio)
    all.nonEmpty && all.forall(b => math.abs(b - bpm) / bpm < 0.04)
  }

  property("keeps the kick tempo when accents sit at four thirds of it") {
    forAllNoShrink(Gen.choose(90.0, 120.0), Gen.choose(1L, 1000L)) { (bpm, seed) =>
      holdsTempo(withAccents(bpm, 4.0 / 3, 1000, seed), bpm)
    }
  }

  property("keeps the kick tempo when accents sit at three halves of it") {
    forAllNoShrink(Gen.choose(90.0, 115.0), Gen.choose(1L, 1000L)) { (bpm, seed) =>
      holdsTempo(withAccents(bpm, 3.0 / 2, 3000, seed), bpm)
    }
  }

  property("keeps the kick tempo when accents sit at two thirds or three quarters of it") {
    forAllNoShrink(Gen.oneOf(2.0 / 3, 3.0 / 4), Gen.choose(100.0, 120.0), Gen.choose(1L, 1000L)) { (ratio, bpm, seed) =>
      holdsTempo(withAccents(bpm, ratio, 1000, seed), bpm)
    }
  }

  property("keeps the full tempo when the kick lands on every other beat") {
    forAllNoShrink(Gen.choose(100.0, 118.0), Gen.choose(1L, 1000L)) { (bpm, seed) =>
      val (audio, _) = Synth.drums(25, _ => bpm, kickEvery = 2, seed = seed)
      holdsTempo(audio, bpm)
    }
  }

  private def kickMoves(audio: Array[Float]): Vector[TempoNews] =
    Synth.readings(audio).flatMap(_._2.news).collect { case moved: TempoNews.KickMoved => moved }

  test("reports one kick move, from four thirds of the kick tempo to the kick tempo, when the first lock comes from it") {
    val readings = Synth.readings(withAccents(100, 4.0 / 3, 1000, 3L))
    val firstLock = readings.find(_._2.beat.isDefined).get._2
    assertEquals(firstLock.news.size, 1)
    kickMoves(withAccents(100, 4.0 / 3, 1000, 3L)) match {
      case Vector(TempoNews.KickMoved(from, to)) =>
        assertEqualsDouble(from, 400.0 / 3, 4.0)
        assertEqualsDouble(to, 100.0, 2.0)
      case other => fail(s"expected exactly one kick move, got $other")
    }
  }

  test("reports no kick move over a plain steady drum pattern") {
    val (audio, _) = Synth.drums(25, _ => 110, seed = 7L)
    assertEquals(kickMoves(audio), Vector.empty)
  }
}
