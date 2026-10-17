package client

import fs2.io.file.Path
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class TokenFileSpec extends ScalaCheckSuite {

  private val p = Path("/tokens/file")

  private val locations: List[(String, Option[String], Map[String, String], Option[Path])] = List(
    ("env", None, Map("SPOTIFY_TOKEN_FILE" -> "/env/t"), Some(Path("/env/t"))),
    ("config", Some("/cfg/t"), Map.empty, Some(Path("/cfg/t"))),
    ("env beats config", Some("/cfg/t"), Map("SPOTIFY_TOKEN_FILE" -> "/env/t"), Some(Path("/env/t"))),
    ("config beats xdg and home", Some("/cfg/t"), Map("XDG_CONFIG_HOME" -> "/xdg", "HOME" -> "/home/u"), Some(Path("/cfg/t"))),
    ("xdg", None, Map("XDG_CONFIG_HOME" -> "/xdg"), Some(Path("/xdg/spotify-hue-sync/refresh-token"))),
    ("xdg beats home", None, Map("XDG_CONFIG_HOME" -> "/xdg", "HOME" -> "/home/u"), Some(Path("/xdg/spotify-hue-sync/refresh-token"))),
    ("home", None, Map("HOME" -> "/home/u"), Some(Path("/home/u/.config/spotify-hue-sync/refresh-token"))),
    ("blank env falls to config", Some("/cfg/t"), Map("SPOTIFY_TOKEN_FILE" -> "  "), Some(Path("/cfg/t"))),
    ("blank config falls to xdg", Some(" "), Map("XDG_CONFIG_HOME" -> "/xdg"), Some(Path("/xdg/spotify-hue-sync/refresh-token"))),
    ("blank xdg falls to home", None, Map("XDG_CONFIG_HOME" -> "", "HOME" -> "/home/u"),
      Some(Path("/home/u/.config/spotify-hue-sync/refresh-token"))),
    ("blank home gives none", None, Map("HOME" -> " "), None),
    ("nothing gives none", None, Map.empty, None)
  )

  locations.foreach { (name, configured, env, expected) =>
    test(s"locate: $name") {
      assertEquals(TokenFile.locate(configured, env), expected)
    }
  }

  private val graphic = Gen.nonEmptyListOf(Gen.choose('\u0021', '\u007e')).map(_.mkString)

  property("parse reads back what render wrote") {
    forAll(graphic)(t => TokenFile.parse(p, TokenFile.render(t)) == Right(t))
  }

  property("parse accepts a token without a trailing newline") {
    forAll(graphic)(t => TokenFile.parse(p, t) == Right(t))
  }

  test("render is the token plus one newline") {
    assertEquals(TokenFile.render("abc"), "abc\n")
  }

  private val rejected: List[(String, String, TokenFileProblem)] = List(
    ("empty", "", TokenFileProblem.Empty(p)),
    ("only a newline", "\n", TokenFileProblem.Empty(p)),
    ("whitespace only", " \t\r\n ", TokenFileProblem.Empty(p)),
    ("multi-line", "abc\ndef\n", TokenFileProblem.Malformed(p)),
    ("two trailing newlines", "abc\n\n", TokenFileProblem.Malformed(p)),
    ("embedded space", "ab cd\n", TokenFileProblem.Malformed(p)),
    ("leading space", " abc\n", TokenFileProblem.Malformed(p)),
    ("carriage return", "abc\r\n", TokenFileProblem.Malformed(p)),
    ("control character", "ab\u0001cd\n", TokenFileProblem.Malformed(p)),
    ("replacement character", "ab\uFFFDcd\n", TokenFileProblem.Malformed(p)),
    ("non-ascii", "töken\n", TokenFileProblem.Malformed(p))
  )

  rejected.foreach { (name, contents, expected) =>
    test(s"parse rejects $name") {
      assertEquals(TokenFile.parse(p, contents), Left(expected))
    }
  }

  test("no problem's message or context contains the token") {
    val token = "s3cret-token"
    val problems = List(
      TokenFileProblem.Unreadable(p, "java.io.IOException: boom"),
      TokenFileProblem.Empty(p),
      TokenFileProblem.Malformed(p),
      TokenFileProblem.LoosePermissions(p),
      TokenFileProblem.NoPosixPermissions(p),
      TokenFileProblem.Unwritable(p, "java.io.IOException: boom"),
      TokenFileProblem.Undeletable(p, "java.io.IOException: boom"),
      TokenFileProblem.NoLocation
    )
    problems.foreach { problem =>
      assert(!problem.message.contains(token))
      assert(!problem.context.values.exists(_.contains(token)))
    }
    assertEquals(TokenFile.parse(p, s"$token x\n").left.map(_.context.values.mkString).left.exists(_.contains(token)), false)
  }

  test("problem context names the path, and the reason where there is one") {
    assertEquals(TokenFileProblem.Empty(p).context, Map("path" -> p.toString))
    assertEquals(TokenFileProblem.Unwritable(p, "r").context, Map("path" -> p.toString, "reason" -> "r"))
    assertEquals(TokenFileProblem.NoLocation.context, Map.empty[String, String])
  }
}
