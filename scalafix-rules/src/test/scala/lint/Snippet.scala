package lint

import scala.meta.*

object Snippet {

  /** `code` parsed as the Scala 3 file `Snippet.scala`, as Scalafix parses the repo's files. */
  def apply(code: String): Source =
    dialects.Scala3(Input.VirtualFile("Snippet.scala", code)).parse[Source].get
}
