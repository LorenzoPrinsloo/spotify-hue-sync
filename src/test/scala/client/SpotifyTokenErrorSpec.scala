package client

import io.circe.Json
import munit.ScalaCheckSuite
import org.http4s.Status
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class SpotifyTokenErrorSpec extends ScalaCheckSuite {

  private val statuses: Gen[Status] =
    Gen.frequency(
      2 -> Gen.const(Status.BadRequest),
      1 -> Gen.const(Status.Unauthorized),
      3 -> Gen.choose(300, 599).map(code => Status.fromInt(code).getOrElse(Status.BadRequest))
    )

  private val codes: Gen[String] = Gen.oneOf(
    Gen.frequency(3 -> Gen.const("invalid_grant"), 3 -> Gen.oneOf("invalid_client", "invalid_request", "unsupported_grant_type")),
    Gen.asciiPrintableStr,
    Gen.identifier
  )

  private val calls: Gen[String] = Gen.identifier

  private def body(code: String): String = Json.obj("error" -> Json.fromString(code)).noSpaces

  private val nonJsonBodies = Gen.oneOf("", "oops", "<html>Bad Gateway</html>", "{\"error\":", "invalid_grant", "[]", "\"invalid_grant\"")

  private val nonStringErrors = Gen.oneOf(Json.fromInt(400), Json.True, Json.Null, Json.arr(Json.fromString("invalid_grant")))

  property("rejected exactly when the status is 400 and the error is invalid_grant") {
    forAll(calls, statuses, codes) { (call, status, code) =>
      val result = SpotifyTokenError.of(call, status, body(code))
      val expected = status == Status.BadRequest && code == "invalid_grant"
      result match {
        case SpotifyTokenError.Rejected(_, _, _) => expected
        case SpotifyTokenError.Failed(_, _, _) => !expected
      }
    }
  }

  property("400 with invalid_grant is rejected whatever else the body holds") {
    forAll(calls, Gen.asciiPrintableStr) { (call, description) =>
      val json = Json.obj("error" -> Json.fromString("invalid_grant"), "error_description" -> Json.fromString(description))
      SpotifyTokenError.of(call, Status.BadRequest, json.noSpaces) match {
        case SpotifyTokenError.Rejected(_, _, _) => true
        case SpotifyTokenError.Failed(_, _, _) => false
      }
    }
  }

  property("bodies that are not JSON are failed") {
    forAll(calls, statuses, nonJsonBodies) { (call, status, text) =>
      SpotifyTokenError.of(call, status, text) match {
        case SpotifyTokenError.Failed(_, _, _) => true
        case SpotifyTokenError.Rejected(_, _, _) => false
      }
    }
  }

  property("an error that is not a string is failed") {
    forAll(calls, nonStringErrors) { (call, error) =>
      SpotifyTokenError.of(call, Status.BadRequest, Json.obj("error" -> error).noSpaces) match {
        case SpotifyTokenError.Failed(_, _, _) => true
        case SpotifyTokenError.Rejected(_, _, _) => false
      }
    }
  }

  property("call, status and body are carried unchanged") {
    forAll(calls, statuses, codes) { (call, status, code) =>
      val text = body(code)
      SpotifyTokenError.of(call, status, text) match {
        case SpotifyTokenError.Rejected(c, s, b) => (c, s, b) == ((call, status, text))
        case SpotifyTokenError.Failed(c, s, b) => (c, s, b) == ((call, status, text))
      }
    }
  }

  property("the message contains the call and the body") {
    forAll(calls, statuses, codes) { (call, status, code) =>
      val text = body(code)
      val message = SpotifyTokenError.of(call, status, text).getMessage
      message.contains(call) && message.contains(text)
    }
  }

  private val table = List(
    (Status.TooManyRequests, body("rate_limited"), false),
    (Status.BadRequest, body("invalid_client"), false),
    (Status.Unauthorized, body("invalid_client"), false),
    (Status.BadRequest, body("invalid_grant"), true),
    (Status.ServiceUnavailable, "", false)
  )

  table.foreach { (status, text, rejected) =>
    test(s"${status.code} with body '$text' is ${if (rejected) "Rejected" else "Failed"}") {
      val isRejected = SpotifyTokenError.of("Renew", status, text) match {
        case SpotifyTokenError.Rejected(_, _, _) => true
        case SpotifyTokenError.Failed(_, _, _) => false
      }
      assertEquals(isRejected, rejected)
    }
  }
}
