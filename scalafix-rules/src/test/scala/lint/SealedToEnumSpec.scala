package lint

import munit.FunSuite
import scalafix.v1.{Patch, SyntacticDocument}

import scala.meta.inputs.Input

class SealedToEnumSpec extends FunSuite {

  private def flagged(code: String): List[(Option[String], Int, String)] =
    SealedToEnum.flagged(Snippet(code)).map { d =>
      (Some(d.position.input).collect { case file: Input.VirtualFile => file.path }, d.position.startLine + 1, d.message)
    }

  private def at(line: Int, name: String): List[(Option[String], Int, String)] =
    List((Some("Snippet.scala"), line, s"Every subtype of $name is a plain case class or case object; declare $name as an enum."))

  private val enumLike = List(
    ("case objects", "Mode", "sealed trait Mode\ncase object On extends Mode\ncase object Off extends Mode"),
    ("case classes and case objects", "Mode", "sealed trait Mode\ncase class Dim(level: Int) extends Mode\ncase object Off extends Mode"),
    ("cases in its companion", "Mode",
      "sealed trait Mode\nobject Mode {\n  case object On extends Mode\n  final case class Dim(level: Int) extends Mode\n}"),
    ("a case naming it by its full name", "Mode", "sealed trait Mode\ncase object On extends show.Mode\ncase object Off extends Mode"),
    ("a type parameter", "Result",
      "sealed trait Result[+A]\ncase class Found[A](a: A) extends Result[A]\ncase object Missing extends Result[Nothing]"),
    ("members of its own", "Mode",
      "sealed trait Mode { def name: String = toString.toLowerCase }\ncase object On extends Mode\ncase object Off extends Mode"),
    ("an Exception parent", "Failure",
      "sealed trait Failure extends Exception\ncase class Refused(code: Int) extends Failure\ncase object Lost extends Failure"),
    ("empty braces on a case", "Mode", "sealed trait Mode\ncase object On extends Mode {}\ncase object Off extends Mode")
  )

  private val notFlagged = List(
    "a sealed trait with a case object with a body" ->
      "sealed trait Mode\ncase object On extends Mode { def level: Int = 1 }\ncase object Off extends Mode",
    "a sealed trait with a case class with members" ->
      "sealed trait Mode\ncase class Dim(level: Int) extends Mode { val half: Int = level / 2 }\ncase object Off extends Mode",
    "a sealed trait with a case class with a companion" ->
      "sealed trait Mode\ncase class Dim(level: Int) extends Mode\nobject Dim { val full: Dim = Dim(100) }\ncase object Off extends Mode",
    "a sealed trait with a case class with a derives clause" ->
      "sealed trait Mode\ncase class Dim(level: Int) extends Mode derives CanEqual\ncase object Off extends Mode",
    "a sealed trait with a plain class" -> "sealed trait Mode\nclass Dim(level: Int) extends Mode\ncase object Off extends Mode",
    "a sealed trait with a plain object" -> "sealed trait Mode\nobject On extends Mode\ncase object Off extends Mode",
    "a sealed trait with a subtrait" -> "sealed trait Mode\ntrait Lit extends Mode\ncase object Off extends Mode",
    "a sealed trait with an anonymous subclass" -> "sealed trait Mode\ncase object Off extends Mode\nval on: Mode = new Mode {}",
    "a sealed trait with a case that mixes in another parent" ->
      "trait Named\nsealed trait Mode\ncase object On extends Mode with Named\ncase object Off extends Mode",
    "a sealed trait with no subtypes in its file" -> "sealed trait Mode",
    "a trait that isn't sealed, so other files can extend it," -> "trait Mode\ncase object On extends Mode\ncase object Off extends Mode",
    "a sealed class that isn't abstract" -> "sealed class Mode\ncase object On extends Mode\ncase object Off extends Mode"
  )

  enumLike.foreach { (shape, name, code) =>
    test(s"a sealed trait with $shape is flagged at its line, suggesting an enum") {
      assertEquals(flagged(code), at(1, name))
    }
  }

  test("a sealed abstract class whose cases pass it arguments is flagged, suggesting an enum") {
    val code =
      """package client
        |
        |sealed abstract class CoverError(message: String) extends RuntimeException(message) {
        |  def reason: String = getMessage
        |}
        |
        |object CoverError {
        |  case class Refused(url: String, status: Int) extends CoverError(s"$url answered $status")
        |  case object Unreadable extends CoverError("not an image")
        |}""".stripMargin
    assertEquals(flagged(code), at(3, "CoverError"))
  }

  notFlagged.foreach { (shape, code) =>
    test(s"$shape isn't flagged") {
      assertEquals(flagged(code), Nil)
    }
  }

  test("each enum-like sealed type in a file is reported") {
    val code = "sealed trait Mode\ncase object On extends Mode\nsealed trait Level\ncase class At(n: Int) extends Level"
    assertEquals(flagged(code).map(_._2), List(1, 3))
  }

  test("the rule reports an enum-like sealed type and nothing for one that isn't") {
    def fix(code: String): Patch = new SealedToEnum().fix(using SyntacticDocument.fromTree(Snippet(code)))
    assert(fix(enumLike.head._3).nonEmpty)
    assert(fix(notFlagged.head._2).isEmpty)
  }
}
