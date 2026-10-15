package domain.config

import domain.engine.{LightRole, Order, PaletteType, RGB}
import munit.FunSuite
import pureconfig.ConfigSource

class LightShowConfigSpec extends FunSuite {

  private val required =
    """spotify { client-id = "id", client-secret = "secret", refresh-token = "refresh" }
      |hue { zone-id = "zone", creds { address = "10.0.0.2", username = "user", psk = "00ff" } }
      |""".stripMargin

  private val reference = ConfigSource.resources("reference.conf")

  private def load(lightShow: String = ""): Either[String, AppConfig] =
    ConfigSource.string(required + lightShow).withFallback(reference).load[AppConfig].left.map(_.prettyPrint())

  test("reference.conf holds the same defaults as the code") {
    val loaded = load()
    assertEquals(loaded, loaded.map(c => AppConfig(c.spotify, c.hue)))
    val withoutZone = ConfigSource.string(required.replace("zone-id = \"zone\", ", "")).withFallback(reference).load[AppConfig]
    assertEquals(withoutZone.map(_.hue), withoutZone.map(c => HueConfig(c.hue.creds)))
    assertEquals(withoutZone.map(_.hue.zoneId), Right(None))
    assertEquals(load().map(_.lightShow), Right(LightShowConfig()))
    assertEquals(load().map(_.pollingIntervalMs), Right(2000))
    assertEquals(load().map(_.ui), Right(UiConfig()))
  }

  test("the light-show section is optional and defaults to the original behaviour") {
    assertEquals(load().map(_.lightShow), Right(LightShowConfig()))
    assertEquals(LightShowConfig().palette, PaletteType.Analogous)
    assertEquals(LightShowConfig().defaultBpm, 20.0)
    assertEquals(LightShowConfig().syncOffsetMs, -150L)
  }

  test("every palette name can be configured") {
    PaletteType.names.foreach { (name, mode) =>
      assertEquals(load(s"""light-show.palette = "$name"""").map(_.lightShow.palette), Right(mode), name)
    }
  }

  test("an unknown palette name fails and lists the valid names") {
    val result = load("""light-show.palette = "rainbow"""")
    assert(result.left.exists(e => e.contains("rainbow") && e.contains("triadic")), result.toString)
  }

  test("overriding one value keeps the defaults for the rest") {
    val show = load("light-show.pulse.attack = 0.3\nlight-show.default-bpm = 90").map(_.lightShow)
    assertEquals(show.map(_.pulse), Right(PulseSettings().copy(attack = 0.3)))
    assertEquals(show.map(_.defaultBpm), Right(90.0))
    assertEquals(show.map(_.pause), Right(PauseSettings()))
  }

  test("the resting colour is read as a hex colour") {
    assertEquals(load("""light-show.pause.resting-colour = "#102030"""").map(_.lightShow.pause.restingColour), Right(RGB.safe(16, 32, 48).get))
    assert(load("""light-show.pause.resting-colour = "blue"""").isLeft)
  }

  test("invalid tuning is rejected when the config loads") {
    val invalid = List(
      "light-show.pulse.attack = 0.7\nlight-show.pulse.sustain = 0.3",
      "light-show.default-bpm = 0",
      "light-show.beats-per-pulse = -1",
      "light-show.frame-interval-ms = 0",
      "light-show.pulse.decay-curve = 0",
      "light-show.pause.fade-out-ms = -5",
      "light-show.reactive.roles = []",
      "light-show.reactive.roles = [\"rhythm\", \"strobe\"]",
      "light-show.reactive.colour-swell.beats = 0",
      "light-show.reactive.colour-swell.attack = 0.95",
      "light-show.reactive.colour-reach = -1"
    )
    invalid.foreach(c => assert(load(c).isLeft, c))
  }

  test("the shipped application.conf loads, with the beat taken from audio") {
    val shipped = scala.io.Source.fromResource("application.conf").mkString
    val values = Map(
      "SPOTIFY_CLIENT_ID" -> "id", "SPOTIFY_CLIENT_SECRET" -> "secret", "SPOTIFY_REFRESH_TOKEN" -> "r",
      "HUE_ZONE_ID" -> "zone", "HUE_BRIDGE_ADDRESS" -> "10.0.0.2", "HUE_USERNAME" -> "u", "HUE_PSK" -> "00"
    )
    val resolved = values.foldLeft(shipped)((acc, kv) => acc.replace("${" + kv._1 + "}", "\"" + kv._2 + "\""))
    val result = ConfigSource.string(resolved).withFallback(reference).load[AppConfig].left.map(_.prettyPrint())
    assertEquals(result.map(_.lightShow), Right(LightShowConfig(beatSource = BeatSource.Audio)))
    assertEquals(result.map(_.pollingIntervalMs), Right(1000))
  }

  test("analogous-complementary orders are both configurable") {
    assertEquals(load("""light-show.palette = "analogous-complementary-interspersed"""").map(_.lightShow.palette), Right(PaletteType.AnalogousComplementary(Order.Interspersed)))
  }

  test("the beat source and audio input can be configured") {
    val show = load("light-show.beat-source = \"audio\"\nlight-show.audio.device = \"Loopback\"\nlight-show.audio.offset-ms = 40").map(_.lightShow)
    assertEquals(show.map(_.beatSource), Right(BeatSource.Audio))
    assertEquals(show.map(_.audio), Right(AudioSettings(device = "Loopback", offsetMs = 40)))
    assertEquals(load().map(_.lightShow.beatSource), Right(BeatSource.Spotify))
  }

  test("light roles can be configured, with colour spelt either way") {
    assertEquals(load().map(_.lightShow.reactive.roles), Right(List(LightRole.Rhythm, LightRole.Colour)))
    assertEquals(
      load("light-show.reactive.roles = [\"pulse\", \"Rhythm\", \"color\", \"colour\"]").map(_.lightShow.reactive.roles),
      Right(List(LightRole.Pulse, LightRole.Rhythm, LightRole.Colour, LightRole.Colour))
    )
  }

  test("the colour swell can be tuned, keeping the defaults for the rest") {
    assertEquals(load("light-show.reactive.colour-swell.beats = 4").map(_.lightShow.reactive.colourSwell), Right(SwellSettings(beats = 4)))
  }

  test("an unknown beat source or an impossible tempo range is rejected") {
    assert(load("light-show.beat-source = \"microphone\"").isLeft)
    assert(load("light-show.audio.min-bpm = 150\nlight-show.audio.max-bpm = 100").isLeft)
    assert(load("light-show.audio.device = \" \"").isLeft)
  }

  test("the app's config loads from a source, failing loudly when something required is missing") {
    import cats.effect.unsafe.implicits.global
    val loaded = AppConfig.load[cats.effect.IO](ConfigSource.string(required).withFallback(reference)).attempt.unsafeRunSync()
    val broken = AppConfig.load[cats.effect.IO](ConfigSource.string("hue { creds { address = x } }").withFallback(reference)).attempt.unsafeRunSync()
    assertEquals(loaded.map(c => (c.hue.zoneId, c.lightShow, c.ui)), Right((Some("zone"), LightShowConfig(), UiConfig())))
    assert(broken.isLeft, broken.toString)
  }
}
