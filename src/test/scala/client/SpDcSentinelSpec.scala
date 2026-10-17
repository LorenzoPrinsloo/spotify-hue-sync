package client

class SpDcSentinelSpec extends munit.FunSuite {
  test("the test JVM carries the sp_dc sentinel and not a developer's real cookie") {
    assertEquals(sys.env.get("SPOTIFY_SP_DC"), Some("sp-dc-sentinel"))
  }
}
