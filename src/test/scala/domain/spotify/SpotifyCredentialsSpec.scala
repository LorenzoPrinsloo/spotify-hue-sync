package domain.spotify

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class SpotifyCredentialsSpec extends ScalaCheckSuite {

  private val space = Gen.oneOf(' ', '\t', '\n', '\r')
  private val blank = Gen.stringOf(space)

  property("a blank refresh token is not configured") {
    forAll(blank)(b => SpotifyCredentials("id", "secret", b).configuredRefreshToken == None)
  }

  property("a refresh token is configured without its surrounding whitespace") {
    forAll(Gen.identifier, blank, blank) { (core, before, after) =>
      SpotifyCredentials("id", "secret", before + core + after).configuredRefreshToken == Some(core)
    }
  }

  test("the refresh token defaults to empty") {
    assertEquals(SpotifyCredentials("id", "secret").refreshToken, "")
  }
}
