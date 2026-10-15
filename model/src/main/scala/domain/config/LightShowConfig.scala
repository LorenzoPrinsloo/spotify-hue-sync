package domain.config

import domain.engine.PaletteType
import io.circe.Codec

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
) derives Codec.AsObject

object LightShowConfig {

  /** Everything that can't work in these settings, each naming the setting as the config file does; empty if none. */
  def problems(c: LightShowConfig): List[String] = {
    val p = c.pulse
    val swell = c.reactive.colourSwell
    List(
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
  }

  def validate(c: LightShowConfig): Either[String, LightShowConfig] =
    problems(c) match {
      case Nil   => Right(c)
      case found => Left(found.mkString("; "))
    }
}
