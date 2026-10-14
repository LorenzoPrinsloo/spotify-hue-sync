package domain.spotify

import io.circe.parser.decode
import scala.concurrent.duration.*

class PlaybackStateSpec extends munit.FunSuite {

  private val playing =
    """{
      |  "progress_ms": 61500,
      |  "is_playing": true,
      |  "item": {
      |    "id": "4uLU6hMCjMI75M1A2tKUQC",
      |    "album": { "images": [ { "url": "https://i.scdn.co/image/large" }, { "url": "https://i.scdn.co/image/small" } ] }
      |  }
      |}""".stripMargin

  test("decodes the fields the engine needs from Spotify's player response") {
    val state = decode[PlaybackState](playing).toOption.get
    assertEquals(state.trackId.value, "4uLU6hMCjMI75M1A2tKUQC")
    assertEquals(state.progress, 61500.millis)
    assert(state.isPlaying)
    assertEquals(state.albumCoverUrl, "https://i.scdn.co/image/large")
  }

  test("fails when nothing is playing and the response has no track") {
    assert(decode[PlaybackState]("""{ "progress_ms": 0, "is_playing": false, "item": null }""").isLeft)
  }
}
