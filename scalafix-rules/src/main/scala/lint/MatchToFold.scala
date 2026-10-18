package lint

import scala.meta.*
import scalafix.lint.Diagnostic
import scalafix.v1.{Patch, SyntacticDocument, SyntacticRule}

/**
 * Fails the lint on a match, or a pattern-matching function, whose only two cases are `Some` and `None`, or `Left` and
 * `Right`, which reads better as `fold`.
 */
final class MatchToFold extends SyntacticRule("MatchToFold") {

  override def description: String =
    "Reports a match or pattern-matching function with only Some and None, or Left and Right, cases, which reads better as fold."

  override def isLinter: Boolean = true

  override def fix(using doc: SyntacticDocument): Patch =
    Patch.fromIterable(MatchToFold.flagged(doc.tree).map(Patch.lint))
}

object MatchToFold {

  private val TwoWay: List[List[String]] = List(List("Some", "None"), List("Left", "Right"))

  /**
   * Every match and pattern-matching function in `tree` whose two unguarded cases are `Some(x)` and `None`, or `Left(x)`
   * and `Right(y)`, with `x` and `y` a name or `_`.
   */
  def flagged(tree: Tree): List[Diagnostic] =
    tree.collect {
      case m: Term.MatchLike => (m, m.casesBlock.cases)
      case f: Term.PartialFunction => (f, f.cases)
    }.flatMap { (matching, cases) =>
      twoWay(cases).map(pair => Diagnostic("", s"Use fold instead of a match with only ${pair.mkString(" and ")} cases.", matching.pos))
    }

  private def twoWay(cases: List[Case]): Option[List[String]] =
    if (cases.size == 2 && cases.forall(_.cond.isEmpty)) TwoWay.find(_.toSet == cases.flatMap(c => constructor(c.pat)).toSet)
    else None

  private def constructor(pat: Pat): Option[String] = pat match {
    case Term.Name("None") => Some("None")
    case extract: Pat.Extract =>
      (extract.fun, extract.argClause.values) match {
        case (Term.Name(name), List(_: Pat.Var | _: Pat.Wildcard)) => Some(name)
        case _ => None
      }
    case _ => None
  }
}
