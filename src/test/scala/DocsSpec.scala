import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

class DocsSpec extends munit.FunSuite {
  private def lines(file: String): List[String] =
    Files.readAllLines(Paths.get("docs", file)).asScala.toList

  private val staleClaims = List("certificate checks are off", "spotify calls skip verification")

  test("known limitations do not say Spotify certificates go unchecked") {
    val section = lines("how-it-works.md")
      .dropWhile(_.trim != "## Known limitations")
      .drop(1)
      .takeWhile(!_.startsWith("## "))
    assert(section.nonEmpty)
    val offending = section.filter(l => staleClaims.exists(l.toLowerCase.contains))
    assertEquals(offending, Nil)
  }

  test("roadmap has no item to keep certificate checks on for Spotify") {
    assert(!lines("roadmap.md").exists(_.contains("Keep certificate checks on for Spotify")))
  }
}
