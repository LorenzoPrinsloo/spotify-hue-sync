package domain.config

import domain.engine.LightRole
import io.circe.Codec

/**
 * How the show reacts to live audio, beyond the beat.
 *
 * Each light gets a job from `roles`, repeated across the zone in channel order. Rhythm lights pulse with the bar's
 * kick pattern, calmed by `rhythm`. Colour lights swell with `colourSwell`, once every couple of beats, and move up to `colourReach` palette
 * colours along the palette as the music goes higher or lower. Pulse lights do the classic travelling pulse, their palette turning by up to `colourShift`
 * degrees as the treble picks up or drops away.
 *
 * Brightness follows the music's energy, between `quietest` and `loudest` times normal. A big hit (louder than
 * anything in the last few seconds by `hitRatio`) flashes every light towards white by `flash`, fading over
 * `flashDecayMs`; a treble onset (`sparkleRatio` times the usual background) twinkles one light that isn't a rhythm
 * light by `sparkle`, fading over `sparkleDecayMs`.
 */
case class ReactiveSettings(
  enabled: Boolean = true,
  quietest: Double = 0.35,
  loudest: Double = 1.6,
  colourShift: Double = 25.0,
  flash: Double = 0.7,
  flashDecayMs: Long = 200,
  hitRatio: Double = 1.15,
  sparkle: Double = 0.6,
  sparkleDecayMs: Long = 100,
  sparkleRatio: Double = 4.0,
  roles: List[LightRole] = List(LightRole.Rhythm, LightRole.Colour),
  colourReach: Double = 1.0,
  colourSwell: SwellSettings = SwellSettings(),
  rhythm: RhythmSettings = RhythmSettings()
) derives Codec.AsObject
