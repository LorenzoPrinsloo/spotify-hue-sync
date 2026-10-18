package lint

import munit.FunSuite
import scalafix.v1.{Patch, SyntacticDocument}

import scala.meta.inputs.Input

class MatchToFoldSpec extends FunSuite {

  private def flagged(code: String): List[(Option[String], Int, String)] =
    MatchToFold.flagged(Snippet(code)).map { d =>
      (Some(d.position.input).collect { case file: Input.VirtualFile => file.path }, d.position.startLine + 1, d.message)
    }

  private def at(line: Int, cases: String): List[(Option[String], Int, String)] =
    List((Some("Snippet.scala"), line, s"Use fold instead of a match with only $cases cases."))

  private val someAndNone = List(
    "Some then None" -> "val n = o match { case Some(x) => x; case None => 0 }",
    "None then Some" -> "val n = o match { case None => 0; case Some(x) => x }",
    "Some(_) and None" -> "val n = o match { case Some(_) => 1; case None => 0 }",
    "Some and None after a dot" -> "val n = o.match { case Some(x) => x; case None => 0 }",
    "Some then None in a pattern-matching function" -> "val n = os.map { case Some(x) => x; case None => 0 }",
    "None then Some in a pattern-matching function" -> "val n = os.map { case None => 0; case Some(_) => 1 }"
  )

  private val leftAndRight = List(
    "Left then Right" -> "val n = e match { case Left(err) => err.size; case Right(x) => x }",
    "Right then Left" -> "val n = e match { case Right(x) => x; case Left(err) => err.size }",
    "Left(_) and Right(_)" -> "val n = e match { case Left(_) => 0; case Right(_) => 1 }",
    "Left then Right in a pattern-matching function" -> "val n = es.map { case Left(err) => err.size; case Right(x) => x }",
    "Right then Left in a pattern-matching function" -> "val n = es.map { case Right(x) => x; case Left(_) => 0 }"
  )

  private val notFlagged = List(
    "a match with a guard on Some" -> "val n = o match { case Some(x) if x > 0 => x; case None => 0 }",
    "a match with a guard on Left" -> "val n = e match { case Left(err) if err.nonEmpty => 0; case Right(x) => x }",
    "a match with a nested pattern inside Some" -> "val n = o match { case Some((a, b)) => a; case None => 0 }",
    "a match with a bound nested pattern inside Some" -> "val n = o match { case Some(p @ (a, _)) => a; case None => 0 }",
    "a match with a typed pattern inside Left" -> "val n = e match { case Left(err: String) => 0; case Right(x) => x }",
    "a match with a nested pattern inside Right" -> "val n = e match { case Left(_) => 0; case Right(Some(x)) => x }",
    "a match with a catch-all after Some" -> "val n = o match { case Some(x) => x; case _ => 0 }",
    "a match with a catch-all after Left" -> "val n = e match { case Left(_) => 0; case _ => 1 }",
    "a match with three cases" -> "val n = e match { case Right(x) => x; case Left(\"\") => 0; case Left(_) => 1 }",
    "a match with two cases on one constructor" -> "val n = o match { case Some(x) => x; case Some(_) => 0 }",
    "a match on Some and Left" -> "val n = x match { case Some(_) => 0; case Left(_) => 1 }",
    "a pattern-matching function with a guard on Some" -> "val n = os.map { case Some(x) if x > 0 => x; case None => 0 }",
    "a pattern-matching function with a nested pattern inside Left" -> "val n = es.map { case Left(\"\") => 0; case Right(x) => x }",
    "a pattern-matching function with three cases" -> "val n = es.map { case Right(x) => x; case Left(\"\") => 0; case Left(_) => 1 }",
    "a pattern-matching function with one case" -> "val n = es.collect { case Right(x) => x }"
  )

  someAndNone.foreach { (shape, code) =>
    test(s"a match on $shape is flagged at its line, suggesting fold") {
      assertEquals(flagged(s"object Example {\n  $code\n}"), at(2, "Some and None"))
    }
  }

  leftAndRight.foreach { (shape, code) =>
    test(s"a match on $shape is flagged at its line, suggesting fold") {
      assertEquals(flagged(s"object Example {\n\n  $code\n}"), at(3, "Left and Right"))
    }
  }

  notFlagged.foreach { (shape, code) =>
    test(s"$shape isn't flagged") {
      assertEquals(flagged(code), Nil)
    }
  }

  test("a pattern-matching function in an indented block is flagged at its line, suggesting fold") {
    assertEquals(flagged("val n = o.flatMap:\n  case Some(x) => f(x)\n  case None => g"), at(1, "Some and None"))
  }

  test("each flagged match in a file is reported") {
    val code = "val a = o match { case Some(x) => x; case None => 0 }\nval b = e match { case Left(_) => 0; case Right(x) => x }"
    assertEquals(flagged(code).map(_._2), List(1, 2))
  }

  test("the rule reports a flagged match and nothing for one that isn't") {
    def fix(code: String): Patch = new MatchToFold().fix(using SyntacticDocument.fromTree(Snippet(code)))
    assert(fix(someAndNone.head._2).nonEmpty)
    assert(fix(notFlagged.head._2).isEmpty)
  }
}
