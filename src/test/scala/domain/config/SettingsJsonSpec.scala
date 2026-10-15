package domain.config

import io.circe.parser.decode
import io.circe.syntax.*
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import pureconfig.ConfigSource
import testkit.Settings

class SettingsJsonSpec extends ScalaCheckSuite {

  property("settings come back the same from JSON, as the settings page sends and receives them") {
    forAll(Settings.valid, Settings.valid) { (pending, applied) =>
      val view = SettingsView(pending, applied, LightShowConfig())
      decode[SettingsView](view.asJson.noSpaces) == Right(view)
    }
  }

  test("colours, palettes, roles and the beat source are written as the config file writes them") {
    val json = LightShowConfig().asJson
    assertEquals(json.hcursor.downField("palette").as[String], Right("analogous"))
    assertEquals(json.hcursor.downField("beatSource").as[String], Right("spotify"))
    assertEquals(json.hcursor.downField("reactive").downField("roles").as[List[String]], Right(List("rhythm", "colour")))
    assertEquals(json.hcursor.downField("pause").downField("restingColour").as[String], Right("#1e1408"))
  }

  private val required =
    """spotify { client-id = "id", client-secret = "secret", refresh-token = "refresh" }
      |hue { zone-id = "zone", creds { address = "10.0.0.2", username = "user", psk = "00ff" } }
      |""".stripMargin

  property("the copied config loads back as the same settings") {
    forAll(Settings.valid) { settings =>
      val loaded = ConfigSource.string(required + ConfigSnippet.of(settings)).withFallback(ConfigSource.resources("reference.conf")).load[AppConfig]
      loaded.map(_.lightShow) == Right(settings)
    }
  }

  test("the copied config lists only what differs from the defaults") {
    assertEquals(ConfigSnippet.of(LightShowConfig()), "# Every light-show setting is at its default")
    val changed = LightShowConfig().copy(beatsPerPulse = 2, pulse = PulseSettings().copy(attack = 0.3))
    assertEquals(ConfigSnippet.of(changed), "light-show.beats-per-pulse = 2.0\nlight-show.pulse.attack = 0.3")
  }
}
