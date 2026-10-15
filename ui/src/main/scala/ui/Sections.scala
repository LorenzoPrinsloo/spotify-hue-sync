package ui

import domain.config.{AudioSettings, LightShowConfig, PulseSettings, ReactiveSettings, SwellSettings}

/** Every setting on the page, grouped as it's shown. */
object Sections {

  private type Change = (LightShowConfig, Double) => LightShowConfig

  private def pulse(change: (PulseSettings, Double) => PulseSettings): Change =
    (c, v) => c.copy(pulse = change(c.pulse, v))

  private def swell(change: (SwellSettings, Double) => SwellSettings): Change =
    (c, v) => c.copy(reactive = c.reactive.copy(colourSwell = change(c.reactive.colourSwell, v)))

  private def reactive(change: (ReactiveSettings, Double) => ReactiveSettings): Change =
    (c, v) => c.copy(reactive = change(c.reactive, v))

  private def audio(change: (AudioSettings, Double) => AudioSettings): Change =
    (c, v) => c.copy(audio = change(c.audio, v))

  private def millis(v: Double): Long = math.round(v)

  val area: Section = Section(
    "Entertainment area",
    "Where the show plays. The bridge plays one area at a time; when the show moves, the old area's lights go back to how they were.",
    List(Control.Area),
    Nil
  )

  val roles: Section = Section(
    "Light roles",
    "What each light does. The pattern repeats across your lights in channel order.",
    List(Control.Roles),
    List(
      Slider("reactive.colour-reach", "Colour reach", "How many palette colours the colour lights move when the music is at its highest or lowest.",
        0, 3, 0.1, _.reactive.colourReach, reactive((r, v) => r.copy(colourReach = v)))
    )
  )

  val colourSwell: Section = Section(
    "Colour swell",
    "How the colour lights swell: a slow rise, a brief hold at the peak, then a fade.",
    Nil,
    List(
      Slider("reactive.colour-swell.beats", "Beats per swell", "One swell every this many beats, peaking on the beat.",
        0.5, 8, 0.5, _.reactive.colourSwell.beats, swell((s, v) => s.copy(beats = v))),
      Slider("reactive.colour-swell.attack", "Rise", "Fraction of the swell spent rising.",
        0, 0.9, 0.01, _.reactive.colourSwell.attack, swell((s, v) => s.copy(attack = v))),
      Slider("reactive.colour-swell.sustain", "Hold", "Fraction of the swell spent at the peak.",
        0, 0.9, 0.01, _.reactive.colourSwell.sustain, swell((s, v) => s.copy(sustain = v))),
      Slider("reactive.colour-swell.decay-curve", "Fade curve", "Shape of the fade after the peak; higher fades faster.",
        0.5, 5, 0.1, _.reactive.colourSwell.decayCurve, swell((s, v) => s.copy(decayCurve = v))),
      Slider("reactive.colour-swell.rest-level", "Rest brightness", "Brightness between swells, as a multiple of the colour's own.",
        0, 1, 0.01, _.reactive.colourSwell.restLevel, swell((s, v) => s.copy(restLevel = v))),
      Slider("reactive.colour-swell.peak-boost", "Peak boost", "How much the peak adds on top of the rest brightness.",
        0, 2, 0.05, _.reactive.colourSwell.peakBoost, swell((s, v) => s.copy(peakBoost = v)))
    )
  )

  val pulseShape: Section = Section(
    "Pulse",
    "The shape of each pulse on the rhythm and pulse lights.",
    Nil,
    List(
      Slider("beats-per-pulse", "Beats per pulse", "Pulse once every this many beats.",
        0.5, 4, 0.5, _.beatsPerPulse, (c, v) => c.copy(beatsPerPulse = v)),
      Slider("pulse.attack", "Rise", "Fraction of a pulse spent rising.",
        0, 0.9, 0.01, _.pulse.attack, pulse((p, v) => p.copy(attack = v))),
      Slider("pulse.sustain", "Hold", "Fraction of a pulse spent at full brightness.",
        0, 0.9, 0.01, _.pulse.sustain, pulse((p, v) => p.copy(sustain = v))),
      Slider("pulse.decay-curve", "Fade curve", "Shape of the fade after the peak; higher fades faster.",
        0.5, 5, 0.1, _.pulse.decayCurve, pulse((p, v) => p.copy(decayCurve = v))),
      Slider("pulse.rest-level", "Rest brightness", "Brightness between pulses, as a multiple of the colour's own.",
        0, 1, 0.01, _.pulse.restLevel, pulse((p, v) => p.copy(restLevel = v))),
      Slider("pulse.peak-boost", "Peak boost", "How much the peak adds on top of the rest brightness.",
        0, 4, 0.05, _.pulse.peakBoost, pulse((p, v) => p.copy(peakBoost = v))),
      Slider("pulse.stagger", "Wave stagger", "On pulse lights, how far behind the previous light each one pulses, in pulses.",
        0, 0.5, 0.01, _.pulse.stagger, pulse((p, v) => p.copy(stagger = v)))
    )
  )

  val reactions: Section = Section(
    "Reacting to the music",
    "How brightness, flashes and sparkles follow what the music is doing.",
    List(Control.Reactions),
    List(
      Slider("reactive.quietest", "Quietest", "Brightness in the quietest passages, as a multiple of normal.",
        0.05, 1, 0.05, _.reactive.quietest, reactive((r, v) => r.copy(quietest = v))),
      Slider("reactive.loudest", "Loudest", "Brightness in the loudest passages, as a multiple of normal.",
        1, 3, 0.05, _.reactive.loudest, reactive((r, v) => r.copy(loudest = v))),
      Slider("reactive.flash", "Flash", "How far a big hit flashes every light towards white.",
        0, 1, 0.05, _.reactive.flash, reactive((r, v) => r.copy(flash = v))),
      Slider("reactive.flash-decay-ms", "Flash fade (ms)", "How fast a flash fades.",
        20, 1000, 10, _.reactive.flashDecayMs.toDouble, reactive((r, v) => r.copy(flashDecayMs = millis(v)))),
      Slider("reactive.hit-ratio", "Big-hit threshold", "How much louder than the last few seconds counts as a big hit.",
        1.01, 2, 0.01, _.reactive.hitRatio, reactive((r, v) => r.copy(hitRatio = v))),
      Slider("reactive.sparkle", "Sparkle", "How far a hi-hat twinkles a light towards white.",
        0, 1, 0.05, _.reactive.sparkle, reactive((r, v) => r.copy(sparkle = v))),
      Slider("reactive.sparkle-decay-ms", "Sparkle fade (ms)", "How fast a sparkle fades.",
        20, 500, 10, _.reactive.sparkleDecayMs.toDouble, reactive((r, v) => r.copy(sparkleDecayMs = millis(v)))),
      Slider("reactive.sparkle-ratio", "Sparkle threshold", "How far above the usual treble counts as a sparkle.",
        1.5, 10, 0.5, _.reactive.sparkleRatio, reactive((r, v) => r.copy(sparkleRatio = v))),
      Slider("reactive.colour-shift", "Treble colour turn (°)", "On pulse lights, how far the palette turns as the treble picks up or drops away.",
        0, 90, 1, _.reactive.colourShift, reactive((r, v) => r.copy(colourShift = v)))
    )
  )

  val timing: Section = Section(
    "Timing and beat",
    "Lining the lights up with the music, and how the beat tracker searches for the tempo.",
    List(Control.FixedSettings),
    List(
      Slider("audio.offset-ms", "Audio offset (ms)", "Shifts the lights against the detected beat; higher is earlier. Lower it if the lights lead the music.",
        -200, 400, 5, _.audio.offsetMs.toDouble, audio((a, v) => a.copy(offsetMs = millis(v)))),
      Slider("audio.min-bpm", "Slowest tempo", "Slowest tempo the beat tracker looks for.",
        40, 150, 1, _.audio.minBpm, audio((a, v) => a.copy(minBpm = v))),
      Slider("audio.max-bpm", "Fastest tempo", "Fastest tempo the beat tracker looks for.",
        100, 240, 1, _.audio.maxBpm, audio((a, v) => a.copy(maxBpm = v))),
      Slider("audio.hold-seconds", "Hold the beat (s)", "How long to keep the last beat through quiet or unclear passages.",
        0, 30, 1, _.audio.holdSeconds, audio((a, v) => a.copy(holdSeconds = v))),
      Slider("sync-offset-ms", "Spotify offset (ms)", "Without a live beat, shifts the lights against Spotify's position; negative is earlier.",
        -500, 500, 10, _.syncOffsetMs.toDouble, (c, v) => c.copy(syncOffsetMs = millis(v))),
      Slider("default-bpm", "Default tempo", "Tempo used when Spotify can't provide one and there's no live beat.",
        10, 200, 1, _.defaultBpm, (c, v) => c.copy(defaultBpm = v))
    )
  )

  val palette: Section = Section(
    "Palette and pause",
    "The colours each new song gets, and what the lights do while nothing plays.",
    List(Control.Palette, Control.RestingColour),
    List(
      Slider("pause.fade-out-ms", "Fade to rest (ms)", "How long the lights take to fade to the resting colour when the music stops.",
        0, 10000, 100, _.pause.fadeOutMs.toDouble, (c, v) => c.copy(pause = c.pause.copy(fadeOutMs = millis(v))))
    )
  )

  val all: List[Section] = List(area, roles, colourSwell, pulseShape, reactions, timing, palette)
}
