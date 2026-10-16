import beat.BeatState
import munit.FunSuite
import scala.concurrent.duration.*

class SyncEngineLogSpec extends FunSuite {

  private def beat(bpm: Double): Option[BeatState] = Some(BeatState(bpm, Duration.Zero, Duration.Zero, confidence = 3.0))

  private val cases: List[(String, Option[Double], Option[BeatState], Option[Double], Option[String])] = List(
    ("still no beat", None, None, None, None),
    ("locking on", None, beat(124), Some(124.0), Some("Locked onto the beat at")),
    ("losing the beat", Some(124.0), None, None, Some("Lost the beat")),
    ("a wobble of under 3%", Some(124.0), beat(126), Some(124.0), None),
    ("a change of over 3%", Some(124.0), beat(128), Some(128.0), Some("Tempo changed from")),
    ("dropping to half time", Some(124.0), beat(62), Some(62.0), Some("Tempo changed from"))
  )

  cases.foreach { (name, logged, latest, remembered, message) =>
    test(s"beatNews: $name") {
      val (next, news) = SyncEngine.beatNews(logged, latest)
      assertEquals(next, remembered)
      assertEquals(news.isDefined, message.isDefined, news)
      message.foreach(start => assert(news.exists(_.startsWith(start)), news))
    }
  }

  test("a slow drift is logged once it adds up to more than 3% since the last tempo logged") {
    val tempos = List(124.0, 125.0, 126.0, 127.0, 128.0, 129.0)
    val logged =
      tempos.scanLeft((Option.empty[Double], Option.empty[String])) { case ((last, _), bpm) => SyncEngine.beatNews(last, beat(bpm)) }.drop(1)
    assertEquals(logged.map(_._2.isDefined), List(true, false, false, false, true, false))
  }
}
