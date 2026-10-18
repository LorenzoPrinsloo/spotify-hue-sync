package client

import cats.effect.{Deferred, IO}
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.{CatsEffectSuite, ScalaCheckSuite}
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

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

class SignInCodesMakeSpec extends ScalaCheckSuite {

  private enum Op derives CanEqual {
    case Attempt
    case Hand(code: String)
  }

  private final case class Model(pending: Option[Int], next: Int, handed: Map[Int, String])

  private final case class Outcome(hands: List[Boolean], waits: List[Option[String]])

  private val opGen: Gen[Op] = Gen.oneOf(Gen.const(Op.Attempt), Gen.identifier.map(Op.Hand(_)))

  private def modelled(ops: List[Op]): Outcome = {
    val (model, hands, ids) = ops.foldLeft((Model(None, 0, Map.empty), List.empty[Boolean], List.empty[Int])) {
      case ((m, hs, is), Op.Attempt) =>
        m.pending.fold((m.copy(pending = Some(m.next), next = m.next + 1), hs, m.next :: is))(id => (m, hs, id :: is))
      case ((m, hs, is), Op.Hand(code)) =>
        m.pending.fold((m, false :: hs, is))(id => (m.copy(pending = None, handed = m.handed + (id -> code)), true :: hs, is))
    }
    Outcome(hands.reverse, ids.reverse.map(model.handed.get))
  }

  private def actual(ops: List[Op]): Outcome =
    TestControl
      .executeEmbed(for {
        codes <- SignInCodes.make[IO]
        results <- ops.traverse {
          case Op.Attempt => codes.attempt.map(Right(_))
          case Op.Hand(code) => codes.hand(code).map(Left(_))
        }
        waits <- results.collect { case Right(wait) => wait }.traverse(_.timeout(1.second).attempt.map(_.toOption))
      } yield Outcome(results.collect { case Left(handed) => handed }, waits))
      .unsafeRunSync()

  property("a hand before any attempt gives false") {
    forAll(Gen.identifier) { code =>
      !SignInCodes.make[IO].flatMap(_.hand(code)).unsafeRunSync()
    }
  }

  property("each attempt yields the first code handed after it opened and later hands give false") {
    forAll(Gen.listOf(opGen)) { ops =>
      actual(ops) == modelled(ops)
    }
  }

  property("an attempt opened after a hand waits for the next hand and never yields the earlier code") {
    forAll(Gen.identifier, Gen.identifier) { (first, second) =>
      val got = TestControl
        .executeEmbed(for {
          codes <- SignInCodes.make[IO]
          early <- codes.attempt
          _ <- codes.hand(first)
          late <- codes.attempt
          before <- late.timeout(1.second).attempt.map(_.toOption)
          _ <- codes.hand(second)
          after <- late
          spent <- early
        } yield (before, after, spent))
        .unsafeRunSync()
      got == ((None, second, first))
    }
  }
}
