package lint

import scala.meta.*
import scalafix.lint.Diagnostic
import scalafix.v1.{Patch, SyntacticDocument, SyntacticRule}

/**
 * Fails the lint on a sealed trait or sealed abstract class whose subtypes are all case classes or case objects with
 * nothing of their own, which reads better as an `enum`.
 */
final class SealedToEnum extends SyntacticRule("SealedToEnum") {

  override def description: String =
    "Reports a sealed trait or abstract class whose subtypes are all plain case classes and case objects, which reads better as an enum."

  override def isLinter: Boolean = true

  override def fix(using doc: SyntacticDocument): Patch =
    Patch.fromIterable(SealedToEnum.flagged(doc.tree).map(Patch.lint))
}

object SealedToEnum {

  /**
   * Every sealed trait or sealed abstract class in `tree` that has subtypes, all of them case classes or case objects
   * extending it alone, with no body, derives clause or companion object. A sealed type's subtypes are all in its file.
   */
  def flagged(tree: Tree): List[Diagnostic] = {
    val templates = tree.collect { case t: Template => t }
    val objectNames = tree.collect { case o: Defn.Object => o.name.value }.toSet
    sealedParents(tree).flatMap { parent =>
      val subtypes = templates.filter(_.inits.exists(init => typeName(init.tpe).contains(parent.value)))
      Option.when(subtypes.nonEmpty && subtypes.forall(isPlainCase(_, objectNames)))(
        Diagnostic("", s"Every subtype of ${parent.value} is a plain case class or case object; declare ${parent.value} as an enum.", parent.pos)
      )
    }
  }

  private def sealedParents(tree: Tree): List[Type.Name] =
    tree.collect {
      case t: Defn.Trait if t.mods.exists(_.is[Mod.Sealed]) => t.name
      case c: Defn.Class if c.mods.exists(_.is[Mod.Sealed]) && c.mods.exists(_.is[Mod.Abstract]) => c.name
    }

  private def isPlainCase(template: Template, objectNames: Set[String]): Boolean = {
    val plainTemplate = template.inits.size == 1 && template.earlyClause.isEmpty && template.body.isEmpty && template.derives.isEmpty
    plainTemplate && template.parent.exists {
      case c: Defn.Class => c.mods.exists(_.is[Mod.Case]) && !objectNames.contains(c.name.value)
      case o: Defn.Object => o.mods.exists(_.is[Mod.Case])
      case _ => false
    }
  }

  private def typeName(tpe: Type): Option[String] = tpe match {
    case name: Type.Name => Some(name.value)
    case select: Type.Select => Some(select.name.value)
    case applied: Type.Apply => typeName(applied.tpe)
    case _ => None
  }
}
