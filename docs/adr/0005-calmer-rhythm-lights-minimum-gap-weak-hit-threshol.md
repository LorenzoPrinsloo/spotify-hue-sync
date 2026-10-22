# ADR 0005: Calmer rhythm lights: minimum gap, weak-hit threshold and bar-to-bar smoothing (#43)

Issue: LorenzoPrinsloo/spotify-hue-sync#43 · Status: proposed

## Context

Rhythm-light brightness is `(restLevel + peakBoost × Pulse.rhythm(groove, beats)) × intensity`. `groove` is `Groove.pattern`: (v − typical)/(loudest − typical) × `playing`, where typical is `sorted(8)`. `intensity` is `reading.energy`, which follows loudness over about 1.5 s. Three things make the lights erratic: nothing spaces the pulses, any sixteenth above typical lights, and both `playing` and `intensity` jump from bar to bar. The tracker reads live settings on every hop through `TrackerSettings.from(live.applied)`.

## Decision

Calm the rhythm lights in the tracker, where the bpm, grid step and per-sixteenth state already are. `Groove` gains a `calm: Calm.State`, updated by a new `at` overload. The pure maths goes in a new `beat.Calm`.
- Threshold: v ≤ t gives 0, otherwise (v − t)/(1 − t). The loudest stays 1, and t = 0 is the identity.
- Gap: d is the fewest sixteenths with d × 15000/bpm ≥ minGapMs. `spaced` merges lit sixteenths fewer than d apart into one pulse on the first, at the group's maximum. `spaced` works on one pattern only, and the pattern is rebuilt every hop, so `held` also enforces the gap over time. `State.lastPulse` is the grid step of the last pulse played. Any slot whose next step falls within d − 1 after it is zeroed, so a regrouped pattern can't add a second peak, across the bar line too. The step is recorded when the grid reaches a lit slot (half a sixteenth early), before the next slot's attack starts.
- Strength: the pattern is scaled by the smoothed `State.strength` instead of `playing`.
- Rhythm intensity: `State.energy` follows `levels.energy` the same way. `AudioReading.rhythmEnergy` carries it, and `Effects.rhythmIntensity` clamps it to quietest–loudest. Only rhythm lights use it. Without this, ±20% bar loudness moves peak brightness by about 15% whatever the pattern does.
- `follow`: each learnt sixteenth moves the value 1/16 of a bar's step towards the target. The step is max(0.02, 0.03 × (1 − s)/s) per bar, but the value never stays more than Jitter = 0.35 from the target.
  - The dead band bounds the slowest recovery. At s = 0.9 a large change closes to within 0.35 at once and finishes at 0.02 a bar, within 18 bars.
  - At the default 0.5, ±20% loudness keeps `playing` (capped at 1) within 0.65–1, so the step applies. Strength moves at most 4.2% of the peak per bar and energy at most 3.8%, under 10% together.
- Drop-out and return: when `playing` or the strength is under 0.4, the strength takes `playing` at once. While s > 0, a strength under 0.15 shows as rest. `recent` halves every bar, so the lights rest by bar 3 and are back above 0.5 within 2 bars.
- At s = 0, strength is `playing` and `rhythmEnergy` is None. With 0/0/0 every step is the identity, so the output equals `pattern` exactly. `Prior` is played unchanged until a bar is learnt.

## Components and call direction

- `model`: new `RhythmSettings`, `ReactiveSettings.rhythm`, three checks in `LightShowConfig.problems`.
- `root`: a `ConfigReader[RhythmSettings]` given, and a `rhythm {}` block in reference.conf.
- `beat`:
  - `TrackerSettings.rhythm`.
  - New `Calm`.
  - `Groove`: `levels`, `playing`, `calm`, the `at(beats, kick, energy, settings, bpm)` overload, `lights` and `rhythmEnergy`.
  - `BeatTracker.step` calls the overload. `reading` emits `_.lights` and `_.rhythmEnergy`.
- `domain.engine`: `Effects.rhythmIntensity` and `rhythmLight`. `SyncEngine.pulseFrame`'s Rhythm case calls `rhythmLight`.
- `ui`: three sliders in `Sections.rhythmAndPulse`.
- Calls: page → LiveSettings → TrackerSettings.from → BeatTracker → Groove → Calm → AudioReading → Effects → SyncEngine.

## Contracts

All changes are additive.
- Config keys: `light-show.reactive.rhythm.min-gap-ms` (Long, 160), `.threshold` (Double, 0.3) and `.smoothing` (Double, 0.5).
- Shared model: `RhythmSettings`, and `ReactiveSettings.rhythm`, added last with a default.
- Settings JSON: `reactive.rhythm {minGapMs, threshold, smoothing}`. The derived codec requires it on POST, which is safe because the page posts back the object it was served. `ConfigSnippet` picks it up as is.
- `AudioReading.rhythmEnergy: Option[Double] = None`, `Effects.rhythmIntensity: Option[Double] = None` and `TrackerSettings.rhythm`, all with defaults.
- `Groove.calm` has a default. The old `at(beats, kick)` and `pattern` are unchanged.
- No environment variables or endpoints change.

## Failure modes

- Config: a bad value is fatal at startup through the existing `CannotConvert`. The page's save rejects it through `SettingsProblems`, so a bad save is recoverable. The messages are:
  - "reactive.rhythm.min-gap-ms must be between 0 and 400"
  - "reactive.rhythm.threshold must be between 0 and 0.9"
  - "reactive.rhythm.smoothing must be between 0 and 0.9"
- The Double checks are written `!(x >= 0 && x <= 0.9)`, so NaN is rejected with the same message.
- Engine: `Calm` is total. t ≤ 0.9 keeps 1 − t > 0, and bpm ≤ 0 gives d = 1. There's no new runtime boundary and so no new error type. Validation stays a `List[String]`.

## Config

reference.conf gets `reactive.rhythm { min-gap-ms = 160, threshold = 0.3, smoothing = 0.5 }`, with comments. The case class defaults match, so a missing block or key gives the defaults. `LightShowConfig.problems` checks the inclusive ranges 0–400, 0–0.9 and 0–0.9 at startup, and the app refuses to start on a bad value. The sliders step by 10, 0.05 and 0.05.

## Observability

No new log lines. Nothing new fails at runtime, and logging per hop would flood the log. A bad value is already named by the existing startup error and the settings-save problems, and the page's config snippet shows any value off its default.

## Program design

`model/src/main/scala/domain/config/RhythmSettings.scala` (new):

```scala
package domain.config

import io.circe.Codec

/**
 * How calmly the rhythm lights follow the kick pattern. Kicks closer together than `minGapMs` merge into one pulse on
 * the first kick's sixteenth, as strong as the strongest. A sixteenth lights only once it stands out by `threshold` of
 * the gap between the bar's typical and loudest sixteenth. `smoothing` slows how far the rhythm lights' strength can
 * move from one bar to the next. 0 switches each one off.
 */
case class RhythmSettings(
  minGapMs: Long = 160,
  threshold: Double = 0.3,
  smoothing: Double = 0.5
) derives Codec.AsObject
```

`src/main/scala/beat/Calm.scala` (new):

```scala
package beat

import domain.config.RhythmSettings

/** Calms the kick pattern for the rhythm lights: weak sixteenths dropped, close kicks merged, strength steadied. */
object Calm {

  /** Below this the kicks have dropped out or are only coming back, and the strength follows them at once. */
  val DropOut: Double = 0.4

  /** While smoothing, a strength below this shows as rest. */
  val Resting: Double = 0.15

  /** How far a smoothed value may move in a bar at smoothing 0.5; it scales with (1 - s) / s. */
  val StepPerBar: Double = 0.03

  /** The least a smoothed value moves in a bar, however high the smoothing, so it always catches up. */
  val MinStepPerBar: Double = 0.02

  /** The furthest a smoothed value stays from what it follows; bar-to-bar jitter is well inside this. */
  val Jitter: Double = 0.35

  /**
   * What the rhythm lights are doing: the kicks' smoothed `strength` and the music's smoothed `energy` (None while
   * not smoothing), the sixteenths `learnt` when they last moved, the grid step of the last pulse played, and the
   * `pattern` being played.
   */
  case class State(
    strength: Double = 0.0,
    energy: Option[Double] = None,
    learnt: Long = 0L,
    lastPulse: Option[Long] = None,
    pattern: Vector[Double] = Vector.empty
  )

  /** `levels` thresholded, scaled by the strength shown and spaced at least `minGapMs` apart at `bpm`. */
  def pattern(levels: Vector[Double], strength: Double, settings: RhythmSettings, bpm: Double): Vector[Double] = ???

  /** 0 up to `t`, then rising to 1 at the loudest: (v - t) / (1 - t). */
  def threshold(level: Double, t: Double): Double = ???

  /** The fewest sixteenths at `bpm` that are at least `minGapMs` long; at least 1. */
  def gapSteps(minGapMs: Long, bpm: Double): Int = ???

  /**
   * Lit sixteenths fewer than `gap` apart, around the bar, merged into one on the group's first, as strong as its
   * strongest. Grouping starts after the longest unlit run; a last group too close to the bar's first joins the one before.
   */
  def spaced(pattern: Vector[Double], gap: Int): Vector[Double] = ???

  /** `pattern` with every slot whose next grid step after `step` is fewer than `gap` after `lastPulse` dark. */
  def held(pattern: Vector[Double], step: Long, lastPulse: Option[Long], gap: Int): Vector[Double] = ???

  /**
   * `current` after `sixteenths` more sixteenths heard, moving towards `target` by at most
   * max([[MinStepPerBar]], [[StepPerBar]] (1 - s) / s) a bar, and never more than [[Jitter]] from it; `target` at s = 0.
   */
  def follow(current: Double, target: Double, smoothing: Double, sixteenths: Long): Double = ???

  /** Like [[follow]], but taking `playing` at once while it or `current` is below [[DropOut]]. */
  def strength(current: Double, playing: Double, smoothing: Double, sixteenths: Long): Double = ???

  /** The strength the lights show: rest below [[Resting]] while smoothing. */
  def shown(strength: Double, smoothing: Double): Double = ???
}
```

Changes to existing files:

```diff
diff --git a/model/src/main/scala/domain/config/ReactiveSettings.scala b/model/src/main/scala/domain/config/ReactiveSettings.scala
index c9d8f14..d0252b5 100644
--- a/model/src/main/scala/domain/config/ReactiveSettings.scala
+++ b/model/src/main/scala/domain/config/ReactiveSettings.scala
@@ -7,7 +7,7 @@ import io.circe.Codec
  * How the show reacts to live audio, beyond the beat.
  *
  * Each light gets a job from `roles`, repeated across the zone in channel order. Rhythm lights pulse with the bar's
- * kick pattern. Colour lights swell with `colourSwell`, once every couple of beats, and move up to `colourReach` palette
+ * kick pattern, calmed by `rhythm`. Colour lights swell with `colourSwell`, once every couple of beats, and move up to `colourReach` palette
  * colours along the palette as the music goes higher or lower. Pulse lights do the classic travelling pulse, their palette turning by up to `colourShift`
  * degrees as the treble picks up or drops away.
  *
@@ -29,5 +29,6 @@ case class ReactiveSettings(
   sparkleRatio: Double = 4.0,
   roles: List[LightRole] = List(LightRole.Rhythm, LightRole.Colour),
   colourReach: Double = 1.0,
-  colourSwell: SwellSettings = SwellSettings()
+  colourSwell: SwellSettings = SwellSettings(),
+  rhythm: RhythmSettings = RhythmSettings()
 ) derives Codec.AsObject
diff --git a/src/main/scala/beat/AudioReading.scala b/src/main/scala/beat/AudioReading.scala
index eaa7e96..a2dd4fa 100644
--- a/src/main/scala/beat/AudioReading.scala
+++ b/src/main/scala/beat/AudioReading.scala
@@ -7,7 +7,8 @@ package beat
  * below 1 quieter or duller, above 1 louder or brighter. `hit` is the latest big hit (a crash or a drop), `sparkle`
  * the latest treble onset (hi-hats, cymbals), and `sparkles` counts them, so each can land on a different light.
  * `pitch` is how high the vocals, leads and chords sit compared with usual for the track, from -1 (well below) to 1
- * (well above). `groove` is the bar's kick pattern (see [[Groove]]), empty without a beat. `news` is what the latest hop has to say about how the tempo was chosen.
+ * (well above). `groove` is the pattern the rhythm lights play (see [[Groove.lights]]), empty without a beat. `news` is what the latest hop has to say about how the tempo was chosen.
+ * `rhythmEnergy` is `energy` smoothed from bar to bar for the rhythm lights; without it they follow `energy`.
  */
 case class AudioReading(
   beat: Option[BeatState],
@@ -18,5 +19,6 @@ case class AudioReading(
   sparkles: Long = 0,
   pitch: Double = 0.0,
   groove: Vector[Double] = Vector.empty,
-  news: Vector[TempoNews] = Vector.empty
+  news: Vector[TempoNews] = Vector.empty,
+  rhythmEnergy: Option[Double] = None
 )
diff --git a/src/main/scala/beat/Groove.scala b/src/main/scala/beat/Groove.scala
index e8980b7..f537a5c 100644
--- a/src/main/scala/beat/Groove.scala
+++ b/src/main/scala/beat/Groove.scala
@@ -1,5 +1,7 @@
 package beat
 
+import domain.config.RhythmSettings
+
 /**
  * The kick pattern of a bar: how hard the kick drum's range hits on each sixteenth of a four-beat bar. It's learnt
  * along the beat grid, so the lights can play it back on time, ahead of each hit, instead of reacting to hits after
@@ -8,7 +10,7 @@ package beat
  * `shape` averages each sixteenth over the last several bars, which is steady enough to show the pattern; `recent`
  * over the last bar or two, which shows whether it's still being played. `usualLift` is how far the pattern usually
  * stands out, `learnt` how many sixteenths have been heard, and `step` the one being heard now, counted along the grid,
- * with the `sum` and `count` of the kick power heard in it so far.
+ * with the `sum` and `count` of the kick power heard in it so far. `calm` is what the rhythm lights make of it.
  */
 case class Groove(
   shape: Vector[Double],
@@ -17,7 +19,8 @@ case class Groove(
   learnt: Long,
   step: Long,
   sum: Double = 0.0,
-  count: Int = 0
+  count: Int = 0,
+  calm: Calm.State = Calm.State()
 ) {
 
   /** Moves on to `beats` beats into the grid, having just heard `kick` power in the kick drum's range. */
@@ -40,6 +43,9 @@ case class Groove(
     }
   }
 
+  /** [[at]], then the rhythm lights calmed by `settings` at `bpm`, with the music's `energy` now. */
+  def at(beats: Double, kick: Double, energy: Double, settings: RhythmSettings, bpm: Double): Groove = ???
+
   /**
    * How strongly each sixteenth of the bar is played, from 0 to 1: the loudest is 1 and anything no louder than the
    * typical sixteenth is 0. While the kicks are quieter than usual, or gone, the whole pattern fades with them. Until
@@ -55,6 +61,18 @@ case class Groove(
       if (lift <= 1e-12) Vector.fill(Groove.Steps)(0.0)
       else shape.map(v => math.max(0.0, math.min(1.0, (v - middle) / lift)) * playing)
     }
+
+  /** Each sixteenth's level between the bar's typical sixteenth (0) and its loudest (1), before the kicks' strength. */
+  def levels: Vector[Double] = ???
+
+  /** How hard the kicks are playing now compared with usual, from 0 to 1. */
+  def playing: Double = ???
+
+  /** The pattern the rhythm lights play: the calmed one, or a plain kick on every beat until a bar is learnt. */
+  def lights: Vector[Double] = ???
+
+  /** The music's energy smoothed for the rhythm lights, once a bar is learnt and while smoothing. */
+  def rhythmEnergy: Option[Double] = ???
 }
 
 object Groove {
diff --git a/src/main/scala/beat/TrackerSettings.scala b/src/main/scala/beat/TrackerSettings.scala
index d60922f..773b941 100644
--- a/src/main/scala/beat/TrackerSettings.scala
+++ b/src/main/scala/beat/TrackerSettings.scala
@@ -1,6 +1,6 @@
 package beat
 
-import domain.config.LightShowConfig
+import domain.config.{LightShowConfig, RhythmSettings}
 import scala.concurrent.duration.*
 
 case class TrackerSettings(
@@ -19,7 +19,8 @@ case class TrackerSettings(
   phaseMemorySeconds: Double = 10.0,
   holdSeconds: Double = 10.0,
   hitRatio: Double = 1.15,
-  sparkleRatio: Double = 4.0
+  sparkleRatio: Double = 4.0,
+  rhythm: RhythmSettings = RhythmSettings()
 ) {
   val frameRate: Double = sampleRate / hop
   val hopDuration: FiniteDuration = (hop / sampleRate).seconds.toNanos.nanos
@@ -42,6 +43,7 @@ object TrackerSettings {
       maxBpm = show.audio.maxBpm,
       holdSeconds = show.audio.holdSeconds,
       hitRatio = show.reactive.hitRatio,
-      sparkleRatio = show.reactive.sparkleRatio
+      sparkleRatio = show.reactive.sparkleRatio,
+      rhythm = show.reactive.rhythm
     )
 }
diff --git a/src/main/scala/domain/config/LightShowConfigReaders.scala b/src/main/scala/domain/config/LightShowConfigReaders.scala
index 49931f2..0b049b5 100644
--- a/src/main/scala/domain/config/LightShowConfigReaders.scala
+++ b/src/main/scala/domain/config/LightShowConfigReaders.scala
@@ -27,6 +27,7 @@ object LightShowConfigReaders {
   given ConfigReader[PauseSettings] = ConfigReader.derived[PauseSettings]
   given ConfigReader[AudioSettings] = ConfigReader.derived[AudioSettings]
   given ConfigReader[SwellSettings] = ConfigReader.derived[SwellSettings]
+  given ConfigReader[RhythmSettings] = ConfigReader.derived[RhythmSettings]
   given ConfigReader[ReactiveSettings] = ConfigReader.derived[ReactiveSettings]
 
   given ConfigReader[LightShowConfig] = ConfigReader.derived[LightShowConfig].emap { c =>
diff --git a/src/main/scala/domain/engine/Effects.scala b/src/main/scala/domain/engine/Effects.scala
index 7f27e02..06b26b7 100644
--- a/src/main/scala/domain/engine/Effects.scala
+++ b/src/main/scala/domain/engine/Effects.scala
@@ -1,14 +1,15 @@
 package domain.engine
 
 import beat.AudioReading
-import domain.config.{ReactiveSettings, SwellSettings}
+import domain.config.{PulseSettings, ReactiveSettings, SwellSettings}
 import scala.concurrent.duration.FiniteDuration
 
 /**
  * How the music shapes the show at one moment: an overall brightness, a turn of the palette's hue, a white flash
  * across every light after a big hit, and a twinkle on one light after a treble onset. With live audio, each light
  * also has a role: `rhythm` is where the show is in the bar's kick pattern, `paletteShift` how many palette colours
- * the colour lights have moved along the palette, and `swell` how they swell.
+ * the colour lights have moved along the palette, and `swell` how they swell. `rhythmIntensity`, when there is one,
+ * is the rhythm lights' brightness in place of `intensity`.
  */
 case class Effects(
   intensity: Double = 1.0,
@@ -18,7 +19,8 @@ case class Effects(
   roles: Vector[LightRole] = Vector.empty,
   rhythm: Option[Rhythm] = None,
   paletteShift: Double = 0.0,
-  swell: SwellSettings = SwellSettings()
+  swell: SwellSettings = SwellSettings(),
+  rhythmIntensity: Option[Double] = None
 ) {
 
   /** The given light's role; without live audio every light pulses. */
@@ -27,6 +29,9 @@ case class Effects(
   /** How far the given light is pushed towards white. */
   def whiteFor(light: Int): Double =
     math.min(1.0, flash + sparkle.filter(_.light == light).fold(0.0)(_.amount))
+
+  /** A rhythm light's brightness multiplier `pulses` pulses in: the kick pattern, or every pulse until there's a live beat. */
+  def rhythmLight(pulses: Double, settings: PulseSettings): Double = ???
 }
 
 object Effects {
```

## Slices

1. **Calmer rhythm lights: min-gap-ms, threshold and smoothing.** The three rhythm settings end to end: config and validation, settings JSON, the calmed pattern and rhythm intensity, the sliders and the docs.
   - On generated audio with kicks a sixteenth apart at a tempo above 94 BPM, at the default minimum gap no two rhythm-light peaks are less than 160 ms apart. Each group of close kicks gives one pulse, on the first kick's sixteenth, at the strength of the group's strongest kick
   - On generated drum-and-bass audio at 174 BPM with kicks an eighth apart, at the default minimum gap each kick gets its own rhythm-light pulse
   - On generated audio with a steady kick plus a bass line at half the kick's power in 40–130 Hz, on sixteenths with no kick (the "and" of each beat and the last sixteenth of the bar), only the kick sixteenths pulse at the default threshold
   - At a threshold of t, a sixteenth that passes the threshold lights in proportion to (v − t)/(1 − t), where v is its level as a fraction of the gap between the bar's typical and loudest sixteenth. The loudest sixteenth in the bar is still full brightness
   - On generated audio with steady kicks whose loudness varies at random by ±20% from bar to bar, at the default smoothing the rhythm lights' peak brightness changes by no more than 10% of the peak between consecutive bars
   - When the kick drops out, at the default smoothing the rhythm lights are at rest within 4 bars. When it returns, they reach at least half strength within 2 bars
   - With minimum gap 0, threshold 0 and smoothing 0, the rhythm lights are exactly as they are today on the same audio
   - With no rhythm settings configured, the app uses minimum gap 160 ms, threshold 0.3 and smoothing 0.5
   - If the minimum gap is outside 0–400 ms, the threshold is outside 0–0.9 or the smoothing is outside 0–0.9, the app refuses to start and names the bad setting
   - The settings page's section for rhythm and pulse lights shows sliders for the minimum gap (0–400 ms, step 10), the threshold (0–0.9, step 0.05) and the smoothing (0–0.9, step 0.05). Saving them changes how the rhythm lights behave
   - docs/how-it-works.md describes the minimum gap, threshold and smoothing settings in its rhythm lights section, including that at smoothing 0.9 a large change shows at once and the rest catches up within 18 bars. Its config table lists reactive.rhythm.min-gap-ms (0–400, 160), reactive.rhythm.threshold (0–0.9, 0.3) and reactive.rhythm.smoothing (0–0.9, 0.5)
   - Tests: A new test helper renders the rhythm light on every hop of Synth.readings, as `Effects.at(...).rhythmLight` at the reading's beat count. Peaks are the local maxima of that level above 0.2. A new Synth generator takes per-slot kick and bass powers, a bpm function and per-bar loudness. Every pattern has at most 7 kick sixteenths a bar, because with 8 or more the typical sixteenth equals the loudest and the pattern is all zero.

RhythmLightsSpec (audio through the tracker, Effects and the renderer, all at the defaults; each asserts some peak level > 0.5 before anything else):
- Criterion 1: kicks on {0,1,4,8,9,12} and {0,4,8,9,12,15} (a pair across the bar line), at 120 and 140 BPM, at a tempo drifting 118→126 BPM, and with the pairs' second kicks at random 0.25–0.45 power (near the threshold). Over the whole run no two peaks are less than 160 ms apart. Pair peaks fall within a hop of the first kick's sixteenth. With pair powers (0.7, 1.0), their height matches the single kicks' within 5%. On master, pairs peak a sixteenth (125 ms) apart.
- Criterion 2: 174 BPM with kicks on {0,2,10} and hats on the eighths. Assert the lock is 174 ± 3 BPM, then 3 peaks above 0.5 per bar. This fails before the change because the setting and `rhythmLight` don't exist.
- Criterion 3: kick on the beats, bass on 2/6/10/14/15 at 0.4/0.45/0.5/0.55/0.6 of the kick's power (mean 0.5). On master, typical is the second-quietest bass sixteenth, so three bass sixteenths light at about 0.09–0.27. After the change no bass sixteenth's level tops 0.02.
- Criterion 5: kicks on the beats at 120 BPM, with per-bar loudness from a seeded Random ±20% and hats scaled too. The peak brightness of consecutive bars differs by ≤ 10%. On master both `playing` and `energy` jump, so it fails.
- Criterion 6: 8 bars of kicks, then 6 bars with hats only, then kicks again. The level is 0 from bar 4 of the drop-out, and some peak level is ≥ 0.5 within 2 bars of the return. On master the strength isn't smoothed, so it fails.
- Criterion 7, at 0/0/0 on several Synth runs: `reading.groove` equals `groove.pattern` on every hop, `rhythmEnergy` is None, and the rendered brightness equals today's `Pulse.brightness(Pulse.rhythm(pattern, ...), intensity)`.

CalmSpec (pure):
- Tables: `gapSteps` at 60, 93.75, 94, 120, 174 and 180 BPM, and 0 ms.
- Properties:
  - `threshold` is 0 at and below t, 1 at 1, equal to (v − t)/(1 − t) above t, and the identity at t = 0.
  - `spaced` output is never closer than the gap cyclically, keeps each group's maximum on its first sixteenth, and is the identity at gap 1.
  - Fed any sequence of patterns along an advancing step, `held` never plays two pulses closer than the gap.
  - `follow` returns the target at s = 0. Otherwise it moves at least min(distance, max(0.02, 0.03(1 − s)/s)) and at most that or the excess over Jitter per 16 sixteenths. `strength` takes `playing` below DropOut.

LightShowConfigSpec:
- Defaults when the block is missing.
- A table of in-range, edge and out-of-range values per key, NaN included, each naming its key.

LiveSettingsSpec (JVM): update with new rhythm values, then startSong, then `TrackerSettings.from(applied).rhythm` equals them. A tracker run on the same audio with threshold 0 and with 0.9 gives different `reading.groove`.

SettingsJsonSpec: round-trip with `rhythm`.

SectionsSpec (Node): the three sliders' keys, min, max and step, and that `set` updates `reactive.rhythm`.

## Alternatives

- Calm in `Effects`/`RenderState` instead: that needs raw levels in `AudioReading` and puts music state into the pause-fade renderer.
- Enforce the gap only by regrouping each pattern: the threshold flicker, the moving unlit run and changes in d all move a group's start between hops, giving a second peak a sixteenth later. `held` closes that.
- Smooth with an EMA: it meets the 10% bound only on average, and it recovers geometrically slowly at s = 0.9.