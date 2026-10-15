package domain.config

import domain.engine.{LightRole, PaletteType, RGB}
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert

case class LightShowConfig(
  palette: PaletteType = PaletteType.Analogous,
  beatSource: BeatSource = BeatSource.Spotify,
  audio: AudioSettings = AudioSettings(),
  reactive: ReactiveSettings = ReactiveSettings(),
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
    val swell = c.reactive.colourSwell
    val problems = List(
    Option.when(c.defaultBpm <= 0)("default-bpm must be positive"),
    Option.when(c.beatsPerPulse <= 0)("beats-per-pulse must be positive"),
    Option.when(c.frameIntervalMs <= 0)("frame-interval-ms must be positive"),
    Option.when(p.attack < 0 || p.sustain < 0)("pulse.attack and pulse.sustain can't be negative"),
    Option.when(p.attack + p.sustain >= 1)("pulse.attack + pulse.sustain must be less than 1"),
    Option.when(p.decayCurve <= 0)("pulse.decay-curve must be positive"),
    Option.when(p.restLevel < 0 || p.peakBoost < 0)("pulse.rest-level and pulse.peak-boost can't be negative"),
    Option.when(c.pause.fadeOutMs < 0)("pause.fade-out-ms can't be negative"),
    Option.when(c.audio.minBpm <= 0 || c.audio.maxBpm <= c.audio.minBpm)("audio.min-bpm must be positive and below audio.max-bpm"),
    Option.when(c.audio.device.trim.isEmpty)("audio.device can't be empty"),
    Option.when(c.audio.holdSeconds < 0)("audio.hold-seconds can't be negative"),
    Option.when(c.reactive.quietest <= 0 || c.reactive.loudest < c.reactive.quietest)("reactive.quietest must be positive and no more than reactive.loudest"),
    Option.when(c.reactive.flash < 0 || c.reactive.flash > 1 || c.reactive.sparkle < 0 || c.reactive.sparkle > 1)("reactive.flash and reactive.sparkle must be between 0 and 1"),
    Option.when(c.reactive.flashDecayMs <= 0 || c.reactive.sparkleDecayMs <= 0)("reactive.flash-decay-ms and reactive.sparkle-decay-ms must be positive"),
    Option.when(c.reactive.hitRatio <= 1 || c.reactive.sparkleRatio <= 1)("reactive.hit-ratio and reactive.sparkle-ratio must be above 1"),
    Option.when(c.reactive.roles.isEmpty)("reactive.roles needs at least one role"),
    Option.when(c.reactive.colourReach < 0)("reactive.colour-reach can't be negative"),
    Option.when(c.reactive.colourSwell.beats <= 0)("reactive.colour-swell.beats must be positive"),
    Option.when(swell.attack < 0 || swell.sustain < 0)("reactive.colour-swell.attack and reactive.colour-swell.sustain can't be negative"),
    Option.when(swell.attack + swell.sustain >= 1)("reactive.colour-swell.attack + reactive.colour-swell.sustain must be less than 1"),
    Option.when(swell.decayCurve <= 0)("reactive.colour-swell.decay-curve must be positive"),
    Option.when(swell.restLevel < 0 || swell.peakBoost < 0)("reactive.colour-swell.rest-level and reactive.colour-swell.peak-boost can't be negative")
    ).flatten
    if (problems.isEmpty) Right(c) else Left(problems.mkString("; "))
  }

  given ConfigReader[PaletteType] = ConfigReader[String].emap { name =>
    PaletteType.parse(name).toRight(CannotConvert(name, "PaletteType", s"expected one of ${PaletteType.names.keys.toList.sorted.mkString(", ")}"))
  }

  given ConfigReader[BeatSource] = ConfigReader[String].emap { name =>
    BeatSource.values.find(_.toString.equalsIgnoreCase(name.trim))
      .toRight(CannotConvert(name, "BeatSource", "expected spotify or audio"))
  }

  given ConfigReader[LightRole] = ConfigReader[String].emap { name =>
    LightRole.parse(name).toRight(CannotConvert(name, "LightRole", "expected rhythm, colour or pulse"))
  }

  given ConfigReader[RGB] = ConfigReader[String].emap { hex =>
    RGB.fromHex(hex).toRight(CannotConvert(hex, "RGB", "expected a colour like #1e1408"))
  }

  given ConfigReader[PulseSettings] = ConfigReader.derived[PulseSettings]
  given ConfigReader[PauseSettings] = ConfigReader.derived[PauseSettings]
  given ConfigReader[AudioSettings] = ConfigReader.derived[AudioSettings]
  given ConfigReader[SwellSettings] = ConfigReader.derived[SwellSettings]
  given ConfigReader[ReactiveSettings] = ConfigReader.derived[ReactiveSettings]

  given ConfigReader[LightShowConfig] = ConfigReader.derived[LightShowConfig].emap { c =>
    validate(c).left.map(reason => CannotConvert("light-show", "LightShowConfig", reason))
  }
}
