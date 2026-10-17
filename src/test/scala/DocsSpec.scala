import munit.FunSuite

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

class DocsSpec extends FunSuite {

  test("the README says SPOTIFY_REFRESH_TOKEN is optional") {
    val row = Files.readAllLines(Paths.get("README.md")).asScala.find(_.contains("`SPOTIFY_REFRESH_TOKEN`"))
    assert(row.exists(_.contains("Optional")), row.toString)
  }
}
