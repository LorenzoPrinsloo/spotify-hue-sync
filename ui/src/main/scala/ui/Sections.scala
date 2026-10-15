package ui

import domain.config.{AudioSettings, BeatSource, LightShowConfig, PulseSettings, ReactiveSettings, SwellSettings}
import domain.engine.LightRole

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

  /**
   * The roles the lights actually have: the role pattern while the show reacts to live audio, otherwise the classic
   * pulse on every light.
   */
  def rolesPlaying(c: LightShowConfig): Set[LightRole] =
    if (reacting(c)) c.reactive.roles.toSet else Set(LightRole.Pulse)

  private def reacting(c: LightShowConfig): Boolean = c.reactive.enabled && c.beatSource == BeatSource.Audio

  /** Unused, with `reason`, while no light plays any of `roles`. */
  private def unlessPlaying(roles: Set[LightRole], reason: String): LightShowConfig => Option[String] =
    c => Option.when(rolesPlaying(c).intersect(roles).isEmpty)(s"$reason, so these change nothing right now.")

  val area: Section = Section(
    title = "Entertainment area",
    intro = "Where the show plays. The bridge plays one area at a time; when the show moves, the old area's lights go back to how they were.",
    controls = List(Control.Area),
    sliders = Nil
  )

  val roles: Section = Section(
    title = "Light roles",
    intro = "What each light does. The pattern repeats across your lights in channel order. The sections below say which lights they change.",
    controls = List(Control.Roles, Control.Reactions),
    sliders = Nil
  )

  val rhythmAndPulse: Section = Section(
    title = "Rhythm and pulse lights",
    intro = "The shape of each pulse on the rhythm and pulse lights.",
    controls = Nil,
    sliders = List(
      Slider(
        key = "beats-per-pulse",
        label = "Beats per pulse",
        help = "Pulse once every this many beats. Rhythm lights follow the kick pattern instead once there's a live beat.",
        min = 0.5, max = 4, step = 0.5,
        get = _.beatsPerPulse,
        set = (c, v) => c.copy(beatsPerPulse = v)
      ),
      Slider(
        key = "pulse.attack",
        label = "Rise",
        help = "Fraction of a pulse spent rising.",
        min = 0, max = 0.9, step = 0.01,
        get = _.pulse.attack,
        set = pulse((p, v) => p.copy(attack = v))
      ),
      Slider(
        key = "pulse.sustain",
        label = "Hold",
        help = "Fraction of a pulse spent at full brightness.",
        min = 0, max = 0.9, step = 0.01,
        get = _.pulse.sustain,
        set = pulse((p, v) => p.copy(sustain = v))
      ),
      Slider(
        key = "pulse.decay-curve",
        label = "Fade curve",
        help = "Shape of the fade after the peak; higher fades faster.",
        min = 0.5, max = 5, step = 0.1,
        get = _.pulse.decayCurve,
        set = pulse((p, v) => p.copy(decayCurve = v))
      ),
      Slider(
        key = "pulse.rest-level",
        label = "Rest brightness",
        help = "Brightness between pulses, as a multiple of the colour's own.",
        min = 0, max = 1, step = 0.01,
        get = _.pulse.restLevel,
        set = pulse((p, v) => p.copy(restLevel = v))
      ),
      Slider(
        key = "pulse.peak-boost",
        label = "Peak boost",
        help = "How much the peak adds on top of the rest brightness.",
        min = 0, max = 4, step = 0.05,
        get = _.pulse.peakBoost,
        set = pulse((p, v) => p.copy(peakBoost = v))
      )
    ),
    unused = unlessPlaying(Set(LightRole.Rhythm, LightRole.Pulse), "No light is a rhythm or pulse light")
  )

  val pulseLights: Section = Section(
    title = "Pulse lights",
    intro = "The classic pulse, travelling across the zone.",
    controls = Nil,
    sliders = List(
      Slider(
        key = "pulse.stagger",
        label = "Wave stagger",
        help = "How far behind the previous light each one pulses, in pulses.",
        min = 0, max = 0.5, step = 0.01,
        get = _.pulse.stagger,
        set = pulse((p, v) => p.copy(stagger = v))
      ),
      Slider(
        key = "reactive.colour-shift",
        label = "Treble colour turn (°)",
        help = "How far the palette turns as the treble picks up or drops away.",
        min = 0, max = 90, step = 1,
        get = _.reactive.colourShift,
        set = reactive((r, v) => r.copy(colourShift = v))
      )
    ),
    unused = unlessPlaying(Set(LightRole.Pulse), "No light is a pulse light")
  )

  val colourLights: Section = Section(
    title = "Colour lights",
    intro = "How the colour lights swell: a slow rise, a brief hold at the peak, then a fade. And how far they move along the palette as the music goes higher or lower.",
    controls = Nil,
    sliders = List(
      Slider(
        key = "reactive.colour-swell.beats",
        label = "Beats per swell",
        help = "One swell every this many beats, peaking on the beat.",
        min = 0.5, max = 8, step = 0.5,
        get = _.reactive.colourSwell.beats,
        set = swell((s, v) => s.copy(beats = v))
      ),
      Slider(
        key = "reactive.colour-swell.attack",
        label = "Rise",
        help = "Fraction of the swell spent rising.",
        min = 0, max = 0.9, step = 0.01,
        get = _.reactive.colourSwell.attack,
        set = swell((s, v) => s.copy(attack = v))
      ),
      Slider(
        key = "reactive.colour-swell.sustain",
        label = "Hold",
        help = "Fraction of the swell spent at the peak.",
        min = 0, max = 0.9, step = 0.01,
        get = _.reactive.colourSwell.sustain,
        set = swell((s, v) => s.copy(sustain = v))
      ),
      Slider(
        key = "reactive.colour-swell.decay-curve",
        label = "Fade curve",
        help = "Shape of the fade after the peak; higher fades faster.",
        min = 0.5, max = 5, step = 0.1,
        get = _.reactive.colourSwell.decayCurve,
        set = swell((s, v) => s.copy(decayCurve = v))
      ),
      Slider(
        key = "reactive.colour-swell.rest-level",
        label = "Rest brightness",
        help = "Brightness between swells, as a multiple of the colour's own.",
        min = 0, max = 1, step = 0.01,
        get = _.reactive.colourSwell.restLevel,
        set = swell((s, v) => s.copy(restLevel = v))
      ),
      Slider(
        key = "reactive.colour-swell.peak-boost",
        label = "Peak boost",
        help = "How much the peak adds on top of the rest brightness.",
        min = 0, max = 2, step = 0.05,
        get = _.reactive.colourSwell.peakBoost,
        set = swell((s, v) => s.copy(peakBoost = v))
      ),
      Slider(
        key = "reactive.colour-reach",
        label = "Colour reach",
        help = "How many palette colours the colour lights move when the music is at its highest or lowest.",
        min = 0, max = 3, step = 0.1,
        get = _.reactive.colourReach,
        set = reactive((r, v) => r.copy(colourReach = v))
      )
    ),
    unused = unlessPlaying(Set(LightRole.Colour), "No light is a colour light")
  )

  val sparkles: Section = Section(
    title = "Sparkles: colour and pulse lights",
    intro = "A hi-hat twinkles one colour or pulse light towards white.",
    controls = Nil,
    sliders = List(
      Slider(
        key = "reactive.sparkle",
        label = "Sparkle",
        help = "How far a hi-hat twinkles a light towards white.",
        min = 0, max = 1, step = 0.05,
        get = _.reactive.sparkle,
        set = reactive((r, v) => r.copy(sparkle = v))
      ),
      Slider(
        key = "reactive.sparkle-decay-ms",
        label = "Sparkle fade (ms)",
        help = "How fast a sparkle fades.",
        min = 20, max = 500, step = 10,
        get = _.reactive.sparkleDecayMs.toDouble,
        set = reactive((r, v) => r.copy(sparkleDecayMs = millis(v)))
      ),
      Slider(
        key = "reactive.sparkle-ratio",
        label = "Sparkle threshold",
        help = "How far above the usual treble counts as a sparkle.",
        min = 1.5, max = 10, step = 0.5,
        get = _.reactive.sparkleRatio,
        set = reactive((r, v) => r.copy(sparkleRatio = v))
      )
    ),
    unused = c =>
      if (!reacting(c)) Some("The show isn't reacting to the music, so these change nothing right now.")
      else unlessPlaying(Set(LightRole.Colour, LightRole.Pulse), "No light is a colour or pulse light")(c)
  )

  val allLights: Section = Section(
    title = "All lights",
    intro = "How brightness and flashes follow the music, the colours each song gets, and what the lights do while nothing plays.",
    controls = List(Control.Palette, Control.SongPalette, Control.RestingColour),
    sliders = List(
      Slider(
        key = "reactive.quietest",
        label = "Quietest",
        help = "Brightness in the quietest passages, as a multiple of normal.",
        min = 0.05, max = 1, step = 0.05,
        get = _.reactive.quietest,
        set = reactive((r, v) => r.copy(quietest = v))
      ),
      Slider(
        key = "reactive.loudest",
        label = "Loudest",
        help = "Brightness in the loudest passages, as a multiple of normal.",
        min = 1, max = 3, step = 0.05,
        get = _.reactive.loudest,
        set = reactive((r, v) => r.copy(loudest = v))
      ),
      Slider(
        key = "reactive.flash",
        label = "Flash",
        help = "How far a big hit flashes every light towards white.",
        min = 0, max = 1, step = 0.05,
        get = _.reactive.flash,
        set = reactive((r, v) => r.copy(flash = v))
      ),
      Slider(
        key = "reactive.flash-decay-ms",
        label = "Flash fade (ms)",
        help = "How fast a flash fades.",
        min = 20, max = 1000, step = 10,
        get = _.reactive.flashDecayMs.toDouble,
        set = reactive((r, v) => r.copy(flashDecayMs = millis(v)))
      ),
      Slider(
        key = "reactive.hit-ratio",
        label = "Big-hit threshold",
        help = "How much louder than the last few seconds counts as a big hit.",
        min = 1.01, max = 2, step = 0.01,
        get = _.reactive.hitRatio,
        set = reactive((r, v) => r.copy(hitRatio = v))
      ),
      Slider(
        key = "pause.fade-out-ms",
        label = "Fade to rest (ms)",
        help = "How long the lights take to fade to the resting colour when the music stops.",
        min = 0, max = 10000, step = 100,
        get = _.pause.fadeOutMs.toDouble,
        set = (c, v) => c.copy(pause = c.pause.copy(fadeOutMs = millis(v)))
      )
    )
  )

  val timing: Section = Section(
    title = "Timing and beat",
    intro = "Lining the lights up with the music, and how the beat tracker searches for the tempo.",
    controls = List(Control.FixedSettings),
    sliders = List(
      Slider(
        key = "audio.offset-ms",
        label = "Audio offset (ms)",
        help = "Shifts the lights against the detected beat; higher is earlier. Lower it if the lights lead the music.",
        min = -200, max = 400, step = 5,
        get = _.audio.offsetMs.toDouble,
        set = audio((a, v) => a.copy(offsetMs = millis(v)))
      ),
      Slider(
        key = "audio.min-bpm",
        label = "Slowest tempo",
        help = "Slowest tempo the beat tracker looks for.",
        min = 40, max = 150, step = 1,
        get = _.audio.minBpm,
        set = audio((a, v) => a.copy(minBpm = v))
      ),
      Slider(
        key = "audio.max-bpm",
        label = "Fastest tempo",
        help = "Fastest tempo the beat tracker looks for.",
        min = 100, max = 240, step = 1,
        get = _.audio.maxBpm,
        set = audio((a, v) => a.copy(maxBpm = v))
      ),
      Slider(
        key = "audio.hold-seconds",
        label = "Hold the beat (s)",
        help = "How long to keep the last beat through quiet or unclear passages.",
        min = 0, max = 30, step = 1,
        get = _.audio.holdSeconds,
        set = audio((a, v) => a.copy(holdSeconds = v))
      ),
      Slider(
        key = "sync-offset-ms",
        label = "Spotify offset (ms)",
        help = "Without a live beat, shifts the lights against Spotify's position; negative is earlier.",
        min = -500, max = 500, step = 10,
        get = _.syncOffsetMs.toDouble,
        set = (c, v) => c.copy(syncOffsetMs = millis(v))
      ),
      Slider(
        key = "default-bpm",
        label = "Default tempo",
        help = "Tempo used when Spotify can't provide one and there's no live beat.",
        min = 10, max = 200, step = 1,
        get = _.defaultBpm,
        set = (c, v) => c.copy(defaultBpm = v)
      )
    )
  )

  val all: List[Section] = List(area, roles, rhythmAndPulse, pulseLights, colourLights, sparkles, allLights, timing)
}
