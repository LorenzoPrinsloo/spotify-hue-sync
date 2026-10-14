package domain.config

import domain.engine.{PaletteType, RGB}
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert

/** Shape of one pulse. `attack` and `sustain` are fractions of a pulse; brightness runs from `restLevel` up to `restLevel + peakBoost`. */
case class PulseSettings(
  stagger: Double = 0.15,
  attack: Double = 0.2,
  sustain: Double = 0.1,
  decayCurve: Double = 2.0,
  restLevel: Double = 0.15,
  peakBoost: Double = 2.5
)

/** What the lights do while nothing is playing: fade to `restingColour` over `fadeOutMs`, then hold it. */
case class PauseSettings(
  restingColour: RGB = RGB.safe(30, 20, 8).get,
  fadeOutMs: Long = 2000
)

case class LightShowConfig(
  palette: PaletteType = PaletteType.Analogous,
  defaultBpm: Double = 20.0,
  beatsPerPulse: Double = 1.0,
  syncOffsetMs: Long = -150,
  frameIntervalMs: Long = 16,
  pulse: PulseSettings = PulseSettings(),
  pause: PauseSettings = PauseSettings()
)

object LightShowConfig {

  def validate(c: LightShowConfig): Either[String, LightShowConfig] = {
    val p = c.pulse
    val problems = List(
    Option.when(c.defaultBpm <= 0)("default-bpm must be positive"),
    Option.when(c.beatsPerPulse <= 0)("beats-per-pulse must be positive"),
    Option.when(c.frameIntervalMs <= 0)("frame-interval-ms must be positive"),
    Option.when(p.attack < 0 || p.sustain < 0)("pulse.attack and pulse.sustain can't be negative"),
    Option.when(p.attack + p.sustain >= 1)("pulse.attack + pulse.sustain must be less than 1"),
    Option.when(p.decayCurve <= 0)("pulse.decay-curve must be positive"),
    Option.when(p.restLevel < 0 || p.peakBoost < 0)("pulse.rest-level and pulse.peak-boost can't be negative"),
    Option.when(c.pause.fadeOutMs < 0)("pause.fade-out-ms can't be negative")
    ).flatten
    if (problems.isEmpty) Right(c) else Left(problems.mkString("; "))
  }

  given ConfigReader[PaletteType] = ConfigReader[String].emap { name =>
    PaletteType.parse(name).toRight(CannotConvert(name, "PaletteType", s"expected one of ${PaletteType.names.keys.toList.sorted.mkString(", ")}"))
  }

  given ConfigReader[RGB] = ConfigReader[String].emap { hex =>
    RGB.fromHex(hex).toRight(CannotConvert(hex, "RGB", "expected a colour like #1e1408"))
  }

  given ConfigReader[PulseSettings] = ConfigReader.derived[PulseSettings]
  given ConfigReader[PauseSettings] = ConfigReader.derived[PauseSettings]

  given ConfigReader[LightShowConfig] = ConfigReader.derived[LightShowConfig].emap { c =>
    validate(c).left.map(reason => CannotConvert("light-show", "LightShowConfig", reason))
  }
}
