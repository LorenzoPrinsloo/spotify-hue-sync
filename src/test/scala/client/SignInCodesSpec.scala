package client

import cats.effect.{Deferred, IO}
import cats.effect.testkit.TestControl
import munit.CatsEffectSuite

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

class SignInCodesSpec extends CatsEffectSuite {

  private val fresh = Deferred[IO, String].map(SignInCodes.once[IO])

  test("the first code handed is taken and a second is not") {
    for {
      codes <- fresh
      first <- codes.hand("a")
      second <- codes.hand("b")
    } yield assertEquals((first, second), (true, false))
  }

  test("the attempt gives the code handed over") {
    for {
      codes <- fresh
      _ <- codes.hand("a")
      code <- codes.attempt.flatten
    } yield assertEquals(code, "a")
  }

  test("the attempt waits until a code is handed over") {
    TestControl
      .executeEmbed(fresh.flatMap(_.attempt.flatMap(_.timeout(1.second).attempt)))
      .map {
        case Left(_: TimeoutException) => ()
        case other => fail(s"expected a timeout, got $other")
      }
  }
}
