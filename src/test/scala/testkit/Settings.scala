package testkit

import domain.config.*
import domain.engine.{LightRole, PaletteType}
import org.scalacheck.Gen

/** Generated light-show settings that can all work, from across each setting's range. */
object Settings {

  private def shape: Gen[(Double, Double)] = for {
    attack <- Gen.choose(0.0, 0.9)
    sustain <- Gen.choose(0.0, 0.98 - attack)
  } yield (attack, sustain)

  val valid: Gen[LightShowConfig] = for {
    palette <- Gen.oneOf(PaletteType.names.values.toList)
    source <- Gen.oneOf(BeatSource.values.toList)
    minBpm <- Gen.choose(40.0, 150.0)
    maxBpm <- Gen.choose(minBpm + 1, 240.0)
    audio = AudioSettings("BlackHole", 0L, minBpm, maxBpm, 0.0)
    offset <- Gen.choose(-200L, 400L)
    hold <- Gen.choose(0.0, 30.0)
    (pulseAttack, pulseSustain) <- shape
    pulse <- Gen.zip(Gen.choose(0.0, 0.5), Gen.choose(0.1, 5.0), Gen.choose(0.0, 1.0), Gen.choose(0.0, 4.0))
      .map((stagger, curve, rest, boost) => PulseSettings(stagger, pulseAttack, pulseSustain, curve, rest, boost))
    (swellAttack, swellSustain) <- shape
    swell <- Gen.zip(Gen.choose(0.5, 8.0), Gen.choose(0.1, 5.0), Gen.choose(0.0, 1.0), Gen.choose(0.0, 2.0))
      .map((beats, curve, rest, boost) => SwellSettings(beats, swellAttack, swellSustain, curve, rest, boost))
    roles <- Gen.choose(1, 4).flatMap(n => Gen.listOfN(n, Gen.oneOf(LightRole.values.toList)))
    quietest <- Gen.choose(0.05, 1.0)
    reactive <- Gen.zip(Gen.oneOf(true, false), Gen.choose(quietest, 3.0), Gen.choose(0.0, 90.0), Gen.choose(0.0, 1.0), Gen.choose(1.01, 2.0), Gen.choose(1.5, 10.0))
      .map((enabled, loudest, shift, flash, hitRatio, sparkleRatio) =>
        ReactiveSettings(enabled, quietest, loudest, shift, flash, 200, hitRatio, 0.6, 100, sparkleRatio, roles, 1.0, swell))
    resting <- Colours.rgb
    fade <- Gen.choose(0L, 10000L)
    numbers <- Gen.zip(Gen.choose(10.0, 200.0), Gen.choose(0.5, 4.0), Gen.choose(-500L, 500L))
    area <- Gen.option(Gen.oneOf("Muziekruimte Boven", "Living room", "Lorenzo"))
  } yield {
    val (defaultBpm, beatsPerPulse, syncOffset) = numbers
    LightShowConfig(palette, source, audio.copy(offsetMs = offset, holdSeconds = hold), reactive, defaultBpm, beatsPerPulse, syncOffset, 16, pulse, PauseSettings(resting, fade), area)
  }
}
