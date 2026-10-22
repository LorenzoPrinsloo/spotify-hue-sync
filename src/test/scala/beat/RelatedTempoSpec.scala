package beat

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import scala.concurrent.duration.*

class RelatedTempoSpec extends ScalaCheckSuite {

  override def scalaCheckTestParameters = super.scalaCheckTestParameters.withMinSuccessfulTests(2)
  override val munitTimeout: Duration = 2.minutes
  override val scalaCheckInitialSeed: String = org.scalacheck.rng.Seed(20261014L).toBase64

  private type Readings = Vector[(Double, AudioReading)]

  private def tempos(readings: Readings): Vector[Double] =
    readings.dropWhile(_._2.beat.isEmpty).flatMap(_._2.beat.map(_.bpm))

  private def withAccents(kick: Double, ratio: Double, loudness: Double, seed: Long): Readings = {
    val (kicks, _) = Synth.drums(20, _ => kick, hats = false, seed = seed)
    Synth.readings(Synth.mix(kicks, Synth.accents(20, _ => kick * ratio, loudness = loudness, seed = seed)))
  }

  private def holdsTempo(readings: Readings, bpm: Double): Boolean = {
    val all = tempos(readings)
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
      holdsTempo(Synth.readings(Synth.drums(20, _ => bpm, kickEvery = 2, seed = seed)._1), bpm)
    }
  }

  private def kickMoves(readings: Readings): Vector[TempoNews] =
    readings.flatMap(_._2.news).collect { case moved: TempoNews.KickMoved => moved }

  test("reports one kick move, from four thirds of the kick tempo to the kick tempo, when the first lock comes from it") {
    val readings = withAccents(100, 4.0 / 3, 1000, 3L)
    val firstLock = readings.find(_._2.beat.isDefined).get._2
    assertEquals(firstLock.news.size, 1)
    kickMoves(readings) match {
      case Vector(TempoNews.KickMoved(from, to)) =>
        assertEqualsDouble(from, 400.0 / 3, 4.0)
        assertEqualsDouble(to, 100.0, 2.0)
      case other => fail(s"expected exactly one kick move, got $other")
    }
  }

  private def steady(bpm: Double, seed: Long, seconds: Double): Readings =
    Synth.readings(Synth.drums(seconds, _ => bpm, seed = seed)._1)

  test("reports no kick move over a plain steady drum pattern") {
    assertEquals(kickMoves(steady(110, 7L, 20)), Vector.empty)
  }

  private def within(b: Double, bpm: Double): Boolean = math.abs(b - bpm) / bpm < 0.04

  private def switching(from: Double, to: Double, at: Double, seconds: Double, seed: Long): Readings =
    Synth.readings(Synth.drums(seconds, t => if (t < at) from else to, seed = seed)._1)

  private def reported(readings: Readings, from: Double, to: Double): Vector[(Double, Double)] =
    readings.collect { case (t, r) if t >= from && t <= to && r.beat.isDefined => (t, r.beat.get.bpm) }

  property("reports the real tempo from the first lock to the end of a steady clip") {
    forAllNoShrink(Gen.choose(90.0, 150.0), Gen.choose(1L, 1000L), Gen.choose(20.0, 25.0)) { (bpm, seed, seconds) =>
      holdsTempo(steady(bpm, seed, seconds), bpm)
    }
  }

  property("keeps the old tempo for four seconds after a switch to three quarters of it, then follows within twenty") {
    forAllNoShrink(Gen.choose(1L, 1000L), Gen.choose(10.0, 14.0)) { (seed, at) =>
      val audio = switching(128, 96, at, at + 25, seed)
      reported(audio, at, at + 4).forall((_, b) => within(b, 128)) &&
      reported(audio, at + 20, at + 25).forall((_, b) => within(b, 96))
    }
  }

  property("follows a switch to three halves of the tempo within twenty seconds") {
    forAllNoShrink(Gen.choose(1L, 1000L)) { seed =>
      val audio = switching(96, 144, 8, 33, seed)
      reported(audio, 8, 12).forall((_, b) => within(b, 96)) && reported(audio, 28, 33).forall((_, b) => within(b, 144))
    }
  }

  property("keeps the tempo through a four second passage at three quarters of it") {
    forAllNoShrink(Gen.choose(1L, 1000L), Gen.choose(10.0, 14.0)) { (seed, at) =>
      val audio = Synth.readings(Synth.drums(30, t => if (t >= at && t < at + 4) 96 else 128, seed = seed)._1)
      holdsTempo(audio, 128)
    }
  }

  test("follows a change from 100 to 128 BPM, which isn't related") {
    assert(!BeatTracker.related(100, 128))
    val audio = switching(100, 128, 10, 32, 5L)
    assert(within(audio.last._2.beat.get.bpm, 128))
    assert(reported(audio, 28, 32).forall((_, b) => within(b, 128)))
  }

  test("follows an unrelated change from 100 to 140 BPM") {
    assert(!BeatTracker.related(100, 140))
    assert(within(switching(100, 140, 10, 32, 6L).last._2.beat.get.bpm, 140))
  }

  test("relates the tempos at the ratios and no others") {
    assert(Vector(50.0, 66.7, 75.0, 133.3, 150.0, 200.0).forall(BeatTracker.related(100, _)))
    assert(Vector(100.0, 90.0, 110.0, 128.0, 160.0).forall(!BeatTracker.related(100, _)))
  }

  test("reports exactly one followed related tempo over a switch, from the old tempo to the new one") {
    val news = switching(128, 96, 10, 36, 1L).flatMap(_._2.news).collect { case n: TempoNews.FollowedRelated => n }
    assertEquals(news.size, 1)
    assertEqualsDouble(news.head.from, 128.0, 3.0)
    assertEqualsDouble(news.head.to, 96.0, 3.0)
  }
}
