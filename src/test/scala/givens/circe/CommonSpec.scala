package givens.circe

import givens.circe.common.given
import io.circe.Json
import io.circe.syntax.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import scala.concurrent.duration.*

class CommonSpec extends ScalaCheckSuite {

  property("a duration is written and read as whole seconds, as Spotify's expires_in is") {
    forAll(Gen.choose(0, 100000)) { seconds =>
      val json = seconds.seconds.asJson
      json == Json.fromInt(seconds) && json.as[FiniteDuration] == Right(seconds.seconds)
    }
  }
}
