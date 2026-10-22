# ADR 0006: Accent the downbeat from the harmony's bar position, by dimming the rest of the bar

Issue: LorenzoPrinsloo/spotify-hue-sync#12 · Status: proposed

## Context

With live beat detection every pulse lands on a beat, but the app doesn't know which beat starts the bar, so all four beats pulse the same. The change in harmony from beat to beat shows the bar position in about 3 of 5 songs, and the bass never does. So the accent may only show while the harmony gives a consistent position. The rest of the time the show must stay exactly as it is today, and the same goes for beats-per-pulse above 1, Spotify timing and reactive off.

## Decision

Find the bar position in the tracker and apply it when rendering.
- **Chroma.** `OnsetDetector` keeps the last 4096 samples (bins about 11 Hz apart) and takes a 12-bin chroma from 140 Hz to 2 kHz. Each bin's power is spread over the pitch classes its band covers, in proportion to the overlap in log frequency, so roots and thirds below 700 Hz go to the right class.
- **Votes.** The new pure `beat.Downbeat` is stepped by `BeatTracker` alongside `Groove` and restarted on every new lock. It averages chroma per beat and scores each beat's change as 1 − cosine against the previous beat. At each groove bar line the bar votes for the slot (0–3) whose change is ≥ `MinChange` and beats the next biggest by `Margin`. A partial bar, equal changes or no change give no vote.
- **Rules.** The accent switches **on** at s when the window holds 8 votes and at least 7 are s. It **moves** to t when the last 4 votes are all t. For **off**, a run starts at the first bar that doesn't vote s, at that bar's beat ≡ s (`missed`). The run ends when a bar votes s and s holds at least 3 of the last 4 votes, so one odd bar is harmless. The accent goes off at exactly `missed + 4·8`, so it's dark once 8 bars have passed. That is decided at the last bar line before it lands, and once decided it's final.
- **Landing at a bar line.** The lights run `lead` = `audio.offset-ms` beats ahead of capture, through the new `TrackerSettings.leadMs`. On and move land on the first beat ≡ the new slot strictly after `count + lead`. A change waiting to land blocks any new decision. `BarAccent(slot, before, fromBeat)` gives `before` for beats before `fromBeat` and `slot` from it on.
- **Pulse lights** take their beat from the count since lock: `b = floor(rhythm.beats − lag + attack)`. `pulses` is used only for the envelope phase, because the whole part of `beatsAt` restarts with each estimate. When b is the downbeat or the accent is off, the level is unscaled. Otherwise it's scaled by 1 − strength, so beats 2–4 at strength 1 sit at rest.
- **Rhythm lights** use `Pulse.accentFactor`, which scales the whole rhythm envelope. It's 1 nearer a downbeat's first sixteenth than any other sixteenth with groove weight > 0 (the groove repeats bar on bar), or when the accent is off at `floor(beats)`. Elsewhere it's 1 − strength, with ties dimmed. So beat one peaks exactly at today's level, which covers criterion 3, and every other hit peaks at (1 − strength) × today's, its tail included, which gives rest at strength 1 (criterion 12). The cost is a step down halfway to the next hit.
- Only the level is scaled, never the rest level, the colour or the white. `Effects.forPulsesOf(bpp)` drops the accent unless bpp = 1. The colour branch is untouched, and the groove is not rotated: slot s is sixteenth 4·s of the groove bar.

## Components and call direction

Capture → `OnsetDetector.step` (chroma) → `BeatTracker.step` (lead from `TrackerSettings.leadMs`) → `Downbeat.at` → `AudioReading.accent` → `SyncEngine.followAudio` (`accentNews`) → `playingFrame` → `Effects.at(…).forPulsesOf(bpp)` → `pulseFrame`. In `pulseFrame`, Pulse lights go to `Effects.pulseLight` (→ `Pulse.pulseAt`, `BarAccent.pulsePeak`) and Rhythm lights go to `Effects.rhythmLight` (→ `Pulse.rhythm` overload → `Pulse.accentFactor`). Config flows `ReactiveSettings.downbeatAccent` → `LightShowConfig.problems`, and `AudioSettings.offsetMs` → `TrackerSettings.from` → `leadMs`. The UI adds a slider to `Sections.rhythmAndPulse`. Calls only go downstream. `Downbeat`, `BarAccent`, `Pulse` and `Effects` stay pure.

## Contracts

All additive, each defaulting to today's behaviour:
- Config key `light-show.reactive.downbeat-accent` (Double, 0–1, default 0.3) in `reference.conf`.
- `ReactiveSettings.downbeatAccent` in `model` (shared JVM/JS). Settings JSON gains the field `reactive.downbeatAccent`, and `ConfigSnippet` gets the path `reactive.downbeat-accent` automatically.
- `Onset.chroma = Vector.empty`, `OnsetDetector.chromaHistory = Vector.empty`, `TrackerSettings.leadMs = 0`, `BeatTracker.downbeat = None`, `AudioReading.accent = BarAccent.Off`, and `Effects.accent = Off` with `Effects.accentStrength = 0.0`.
- New: the `Pulse.rhythm` overload, `Pulse.accentFactor`, `Pulse.pulseAt`, `Effects.pulseLight`, `Effects.forPulsesOf` and `SyncEngine.accentNews`.
No environment variables or HTTP endpoints change.

## Failure modes

- **Config:** a value out of range or NaN adds "reactive.downbeat-accent must be between 0 and 1" to `LightShowConfig.problems`. At startup that's fatal through the existing pureconfig `CannotConvert`. On the settings page it's recoverable: `LiveSettings.update` returns `Left` and the old settings stay.
- **Analysis:** there's no I/O. Silence gives empty chroma, which casts no vote. A grid jump leaves a partial bar, which casts no vote. Losing the beat drops `downbeat` (accent off), and a new lock restarts the search.
- **A lead of more than a bar** (very large `audio.offset-ms`): changes still land on a bar line of the new slot, just later.
- **Audio stream failure:** as today, the reading is cleared and the accent goes with it.
There's no new sealed error type, because the only failure is validation, which uses the existing list of problems.

## Config

`reactive.downbeat-accent = 0.3` in `reference.conf`, with the comment "Once the harmony shows where the bar starts, dims every rhythm hit but the bar's first sixteenth, and the pulses on beats 2–4, by this much; only with beats-per-pulse 1. 0 is off". `LightShowConfig.problems` checks it as `!(v >= 0 && v <= 1)`. The UI slider runs from 0 to 1 in steps of 0.05. `TrackerSettings.from` copies `audio.offset-ms` into `leadMs` and needs no new validation.

## Observability

INFO from `followAudio` via `SyncEngine.accentNews`, logged only when the target slot changes, with beats numbered 1–4 on the tracker's bar:
- "Downbeat accent on beat {n} of the bar", with fields `accent_beat` and `from_beat`.
- "Downbeat accent moved from beat {a} to beat {b}", with fields `previous_beat`, `accent_beat` and `from_beat`.
- "Downbeat accent off", with fields `previous_beat` and `from_beat` (a lost or new lock logs this too).
Nothing is logged per frame or per vote.

## Program design

`src/main/scala/beat/BarAccent.scala` (new):

```scala
package beat

/**
 * Which beat of the tracker's four-beat bar the harmony says starts the bar, if any. It changes only at a bar line:
 * beats counted from `fromBeat` on take `slot`, earlier ones `before`. `fromBeat` is always a beat of the bar line it
 * lands on, ahead of where the lights were when it was decided.
 */
case class BarAccent(slot: Option[Int] = None, before: Option[Int] = None, fromBeat: Long = 0L) {

  /** The accented beat of the bar (0 to 3) for the beat counted `beat` since locking on; None while the accent is off. */
  def at(beat: Long): Option[Int] = ???

  /** Whether the beat counted `beat` since locking on is the first beat of an accented bar. */
  def downbeat(beat: Long): Boolean = ???

  /** How much of its peak boost the pulse on the beat counted `beat` keeps: all of it on the downbeat or with the accent off, otherwise `1 - strength`. */
  def pulsePeak(beat: Long, strength: Double): Double = ???
}

object BarAccent {

  val Off: BarAccent = BarAccent()
}
```

`src/main/scala/beat/Downbeat.scala` (new):

```scala
package beat

/**
 * Finds which beat starts the bar from how much the harmony changes from one beat to the next.
 *
 * `beat` is the beat being heard, counted along the grid, with the `sum` of its chroma over `hops` hops so far; `last`
 * is the previous beat's average chroma. `changes` holds how much the harmony changed on each beat of the grid's bar so
 * far, by its place in the bar, and `votes` which beat each recent bar pointed at, if any, newest last. `missed` is the
 * beat on which the accented beat first went missing, while it hasn't held since. `accent` is what the lights play.
 */
case class Downbeat(
  beat: Long,
  sum: Vector[Double],
  hops: Int,
  last: Option[Vector[Double]],
  changes: Map[Int, Double],
  votes: Vector[Option[Int]],
  missed: Option[Long],
  accent: BarAccent
) {

  /**
   * Moves on to `beats` beats into the grid, having just heard `chroma`, with the lights playing `lead` beats ahead of
   * what's heard. At each bar line of the grid the bar votes, and the accent is decided.
   */
  def at(beats: Double, chroma: Vector[Double], lead: Double): Downbeat = ???
}

object Downbeat {

  /** Bars of votes kept. */
  val Window: Int = 8

  /** Of a full `Window` of bars, how many must point at one beat to switch the accent on. */
  val OnVotes: Int = 7

  /** Bars in a row that must point at another beat before the accent moves there. */
  val MoveBars: Int = 4

  /** The accented beat holds again once it has at least `HeldVotes` of the last `MoveBars` bars. */
  val HeldVotes: Int = 3

  /** Bars from the first one that missed the accented beat until the accent is off, if it hasn't held since. */
  val OffBars: Int = 8

  /** A bar votes only when its biggest change is at least this, so a harmony that never changes doesn't. */
  val MinChange: Double = 0.1

  /** ...and beats the bar's next biggest by this much, so a harmony that changes as much on every beat doesn't. */
  val Margin: Double = 0.1

  /** A fresh search, accent off, at `beats` beats into the grid. */
  def start(beats: Double): Downbeat = ???

  /** How much the harmony changed between two chromas: 1 - their cosine similarity; 0 if either is empty. */
  def change(before: Vector[Double], after: Vector[Double]): Double = ???

  /** The place in the bar (0 to 3) whose change stands out from the bar's `changes`, if one does; None unless all four were heard. */
  def vote(changes: Map[Int, Double]): Option[Int] = ???

  /** The first beat on `slot`'s bar line (counted beat mod 4 = `slot`) strictly after `after` beats. */
  def landing(slot: Int, after: Double): Long = ???

  /**
   * `missed` and the accent after the grid's bar line at beat `line`, given the latest `votes`, newest last, with the
   * lights at `ahead` beats. No decision while a change is still to land; an off, once decided, is final.
   */
  def decide(votes: Vector[Option[Int]], missed: Option[Long], accent: BarAccent, line: Long, ahead: Double): (Option[Long], BarAccent) = ???
}
```

Changes to existing files:

```diff
diff --git a/model/src/main/scala/domain/config/LightShowConfig.scala b/model/src/main/scala/domain/config/LightShowConfig.scala
index c9d6d08..4756b9b 100644
--- a/model/src/main/scala/domain/config/LightShowConfig.scala
+++ b/model/src/main/scala/domain/config/LightShowConfig.scala
@@ -64,7 +64,8 @@ object LightShowConfig {
       ),
       Option.when(!(r.minGapMs >= 0 && r.minGapMs <= 400))("reactive.rhythm.min-gap-ms must be between 0 and 400"),
       Option.when(!(r.threshold >= 0 && r.threshold <= 0.9))("reactive.rhythm.threshold must be between 0 and 0.9"),
-      Option.when(!(r.smoothing >= 0 && r.smoothing <= 0.9))("reactive.rhythm.smoothing must be between 0 and 0.9")
+      Option.when(!(r.smoothing >= 0 && r.smoothing <= 0.9))("reactive.rhythm.smoothing must be between 0 and 0.9"),
+      Option.when(!(c.reactive.downbeatAccent >= 0 && c.reactive.downbeatAccent <= 1))("reactive.downbeat-accent must be between 0 and 1")
     ).flatten
   }
 
diff --git a/model/src/main/scala/domain/config/ReactiveSettings.scala b/model/src/main/scala/domain/config/ReactiveSettings.scala
index d0252b5..be22a08 100644
--- a/model/src/main/scala/domain/config/ReactiveSettings.scala
+++ b/model/src/main/scala/domain/config/ReactiveSettings.scala
@@ -15,6 +15,9 @@ import io.circe.Codec
  * anything in the last few seconds by `hitRatio`) flashes every light towards white by `flash`, fading over
  * `flashDecayMs`; a treble onset (`sparkleRatio` times the usual background) twinkles one light that isn't a rhythm
  * light by `sparkle`, fading over `sparkleDecayMs`.
+ *
+ * Once the harmony shows where the bar starts, `downbeatAccent` scales down the peak of every rhythm hit but the bar's
+ * first sixteenth, and of the pulses on beats two to four; only when every beat pulses. 0 switches it off.
  */
 case class ReactiveSettings(
   enabled: Boolean = true,
@@ -30,5 +33,6 @@ case class ReactiveSettings(
   roles: List[LightRole] = List(LightRole.Rhythm, LightRole.Colour),
   colourReach: Double = 1.0,
   colourSwell: SwellSettings = SwellSettings(),
-  rhythm: RhythmSettings = RhythmSettings()
+  rhythm: RhythmSettings = RhythmSettings(),
+  downbeatAccent: Double = 0.3
 ) derives Codec.AsObject
diff --git a/src/main/scala/SyncEngine.scala b/src/main/scala/SyncEngine.scala
index abb23ba..34bd3c5 100644
--- a/src/main/scala/SyncEngine.scala
+++ b/src/main/scala/SyncEngine.scala
@@ -1,7 +1,7 @@
 import cats.effect.*
 import cats.effect.syntax.all.*
 import cats.syntax.all.*
-import beat.{AudioReading, BeatState, TempoNews}
+import beat.{AudioReading, BarAccent, BeatState, TempoNews}
 import client.{AlbumCovers, CoverError, SpotifyClient, SpotifyTlsFailure}
 import fs2.Stream
 import domain.config.{AppConfig, LightShowConfig, PulseSettings}
@@ -140,13 +140,18 @@ class SyncEngine[F[_]: Async] private (
 
   /**
    * Keeps `audioRef` up to date with what the music is doing, logging when the beat locks on, is lost or changes
-   * tempo. On failure it clears the reading, so the show carries on with Spotify timing and no audio effects.
+   * tempo, and when the downbeat accent switches on, moves or switches off. On failure it clears the reading, so the
+   * show carries on with Spotify timing and no audio effects.
    */
   private def followAudio(audioRef: Ref[F, Option[AudioReading]]): Stream[F, Nothing] =
     audio
-      .evalMapAccumulate(Option.empty[Double]) { (logged, reading) =>
-        val (remembered, news) = SyncEngine.beatNews(logged, reading.beat)
-        ((news.toList ++ reading.news.map(SyncEngine.tempoNewsLine)).traverse_(logger.info(_)) *> audioRef.set(Some(reading))).as((remembered, ()))
+      .evalMapAccumulate((Option.empty[Double], BarAccent.Off)) { (logged, reading) =>
+        val (tempoLogged, accentLogged) = logged
+        val (remembered, news) = SyncEngine.beatNews(tempoLogged, reading.beat)
+        val accentLine = SyncEngine.accentNews(accentLogged, reading.accent)
+        ((news.toList ++ reading.news.map(SyncEngine.tempoNewsLine)).traverse_(logger.info(_)) *>
+          accentLine.traverse_((fields, line) => logger.info(fields)(line)) *>
+          audioRef.set(Some(reading))).as(((remembered, reading.accent), ()))
       }
       .drain
       .handleErrorWith { err =>
@@ -198,7 +203,7 @@ class SyncEngine[F[_]: Async] private (
       }(b => b.beatsAt(heardAt) / show.beatsPerPulse)
       effects = reading
         .filter(_ => show.reactive.enabled)
-        .fold(Effects.neutral)(r => Effects.at(heardAt, r, track.zone.channels.size, show.reactive))
+        .fold(Effects.neutral)(r => Effects.at(heardAt, r, track.zone.channels.size, show.reactive).forPulsesOf(show.beatsPerPulse))
     } yield SyncEngine.pulseFrame(track.zone, track.palette, pulses, show.pulse, effects)
 
 object SyncEngine:
@@ -236,6 +241,12 @@ object SyncEngine:
       case _ => (logged, None)
     }
 
+  /**
+   * What to log about the downbeat accent, given the accent seen last and the latest: that it switched on, moved or
+   * switched off, with its fields, beats numbered 1 to 4. None when its slot hasn't changed.
+   */
+  def accentNews(logged: BarAccent, accent: BarAccent): Option[(Map[String, String], String)] = ???
+
   /** The log line for a tempo decision: both tempos and the ratio of the new one to the old. */
   def tempoNewsLine(news: TempoNews): String =
     news match {
@@ -258,15 +269,15 @@ object SyncEngine:
    *  - a rhythm light pulses with the bar's kick pattern, or on every pulse until there's a live beat;
    *  - a colour light swells once every `swell.beats` beats, peaking on the beat, and moves along the palette as the
    *    music goes higher or lower.
-   * The effects scale the brightness and push lights towards white for flashes and sparkles.
+   * The effects scale the brightness, dim the rest of the bar around the downbeat on pulse and rhythm lights, and push
+   * lights towards white for flashes and sparkles.
    */
   def pulseFrame(zone: Zone, palette: Palette, pulses: Double, settings: PulseSettings, effects: Effects = Effects.neutral): Frame = {
     val updates = zone.channels.zipWithIndex.map { (channel, i) =>
       val (brightness, colour) = effects.roleOf(i) match {
         case LightRole.Pulse =>
           val own = palette.getColor(i)
-          val level = Pulse.onBeat(pulses - i * settings.stagger, settings)
-          (Pulse.brightness(level, settings, effects.intensity), if (effects.hueShift == 0) own else own.rotateHue(effects.hueShift))
+          (effects.pulseLight(i * settings.stagger, pulses, settings), if (effects.hueShift == 0) own else own.rotateHue(effects.hueShift))
         case LightRole.Rhythm =>
           (effects.rhythmLight(pulses, settings), palette.getColor(i))
         case LightRole.Colour =>
diff --git a/src/main/scala/beat/AudioReading.scala b/src/main/scala/beat/AudioReading.scala
index a2dd4fa..b6f9435 100644
--- a/src/main/scala/beat/AudioReading.scala
+++ b/src/main/scala/beat/AudioReading.scala
@@ -9,6 +9,7 @@ package beat
  * `pitch` is how high the vocals, leads and chords sit compared with usual for the track, from -1 (well below) to 1
  * (well above). `groove` is the pattern the rhythm lights play (see [[Groove.lights]]), empty without a beat. `news` is what the latest hop has to say about how the tempo was chosen.
  * `rhythmEnergy` is `energy` smoothed from bar to bar for the rhythm lights; without it they follow `energy`.
+ * `accent` is the beat the harmony says starts the bar (see [[Downbeat]]), off without a beat.
  */
 case class AudioReading(
   beat: Option[BeatState],
@@ -20,5 +21,6 @@ case class AudioReading(
   pitch: Double = 0.0,
   groove: Vector[Double] = Vector.empty,
   news: Vector[TempoNews] = Vector.empty,
-  rhythmEnergy: Option[Double] = None
+  rhythmEnergy: Option[Double] = None,
+  accent: BarAccent = BarAccent.Off
 )
diff --git a/src/main/scala/beat/BeatTracker.scala b/src/main/scala/beat/BeatTracker.scala
index e27da4f..1c1e7ae 100644
--- a/src/main/scala/beat/BeatTracker.scala
+++ b/src/main/scala/beat/BeatTracker.scala
@@ -7,7 +7,7 @@ import scala.concurrent.duration.*
  * tries every candidate beat grid over the last few seconds of onsets and keeps the one whose beats land on the
  * strongest onsets: that grid gives both the tempo and where the beats fall. Through quiet or unclear passages it
  * keeps the last confident beat for `holdSeconds`. Alongside the beat it follows the loudness and treble of the
- * music, and picks out big hits and treble sparkles.
+ * music, picks out big hits and treble sparkles, and finds where the bar starts from the harmony (`downbeat`).
  */
 case class BeatTracker(
   settings: TrackerSettings,
@@ -26,12 +26,13 @@ case class BeatTracker(
   sparkles: Long = 0,
   groove: Option[Groove] = None,
   news: Vector[TempoNews] = Vector.empty,
-  pending: Option[BeatTracker.Pending] = None
+  pending: Option[BeatTracker.Pending] = None,
+  downbeat: Option[Downbeat] = None
 ) {
 
   def reading: AudioReading =
     AudioReading(beat, levels.energy, levels.treble, hit, sparkle, sparkles, levels.pitch, groove.fold(Vector.empty)(_.lights), news,
-      groove.flatMap(_.rhythmEnergy))
+      groove.flatMap(_.rhythmEnergy), downbeat.fold(BarAccent.Off)(_.accent))
 
   def step(frame: AudioFrame): BeatTracker = {
     val s = settings
@@ -77,10 +78,15 @@ case class BeatTracker(
       .standsOut(onset.trebleFlux, treble, s.sparkleRatio * background, sparkle, now, 100.millis)
       .filter(_ => settled && trebleLed)
 
-    // The kick pattern is learnt along the beat grid; a new lock starts a new one, since its beats are counted afresh.
+    // The kick pattern and the bar's first beat are found along the beat grid; a new lock starts both afresh, since its
+    // beats are counted afresh.
     val nextGroove = nextBeat.map { b =>
       groove.filter(_ => beat.isDefined).fold(Groove.start(b.countAt(now)))(_.at(b.countAt(now), onset.kick, nextLevels.energy, s.rhythm, b.bpm))
     }
+    val nextDownbeat = nextBeat.map { b =>
+      val count = b.countAt(now)
+      downbeat.filter(_ => beat.isDefined).fold(Downbeat.start(count))(_.at(count, onset.chroma, s.leadMs * b.bpm / 60000.0))
+    }
 
     copy(
       detector = nextDetector,
@@ -98,7 +104,8 @@ case class BeatTracker(
       sparkles = if (newSparkle.isDefined) sparkles + 1 else sparkles,
       groove = nextGroove,
       news = news,
-      pending = nextPending
+      pending = nextPending,
+      downbeat = nextDownbeat
     )
   }
 }
diff --git a/src/main/scala/beat/Onset.scala b/src/main/scala/beat/Onset.scala
index 5ad5081..4bca89a 100644
--- a/src/main/scala/beat/Onset.scala
+++ b/src/main/scala/beat/Onset.scala
@@ -8,6 +8,15 @@ package beat
  * loudness. `kick` is how much the power in the kick drum's range (about 40-130 Hz) rose since the previous window:
  * unlike the flux, it isn't compressed, so the big low thump of a kick stands out from the bass line around it.
  * `pitch` is where the sound in the middle of the spectrum (vocals, leads, chords) is centred, in octaves above 1 Hz,
- * or None when that range is silent.
+ * or None when that range is silent. `chroma` is how loud each of the twelve pitch classes is where chords sit
+ * (see [[OnsetDetector.chroma]]), from C, summing to 1, or empty when it's silent.
  */
-case class Onset(flux: Double, bassFlux: Double, trebleFlux: Double, rms: Double, kick: Double, pitch: Option[Double])
+case class Onset(
+  flux: Double,
+  bassFlux: Double,
+  trebleFlux: Double,
+  rms: Double,
+  kick: Double,
+  pitch: Option[Double],
+  chroma: Vector[Double] = Vector.empty
+)
diff --git a/src/main/scala/beat/OnsetDetector.scala b/src/main/scala/beat/OnsetDetector.scala
index 3d8ae31..f7fa329 100644
--- a/src/main/scala/beat/OnsetDetector.scala
+++ b/src/main/scala/beat/OnsetDetector.scala
@@ -3,9 +3,10 @@ package beat
 /**
  * Spectral-flux onset detection: how much louder each frequency got since the previous window. Drums and note
  * attacks make it spike; steady sounds keep it near zero. The low end counts most, because the kick and bass
- * usually carry the beat while hi-hats fill in between.
+ * usually carry the beat while hi-hats fill in between. `chromaHistory` is the longer stretch of audio the chroma is
+ * taken from.
  */
-case class OnsetDetector(window: Int, history: Vector[Float], previous: Vector[Double]) {
+case class OnsetDetector(window: Int, history: Vector[Float], previous: Vector[Double], chromaHistory: Vector[Float] = Vector.empty) {
 
   /** Feeds one hop of new samples; returns the next state and what the hop sounded like. */
   def step(hop: Array[Float]): (OnsetDetector, Onset) = {
@@ -25,8 +26,9 @@ case class OnsetDetector(window: Int, history: Vector[Float], previous: Vector[D
       }
     val rms = if (hop.isEmpty) 0.0 else math.sqrt(hop.map(s => s.toDouble * s).sum / hop.length)
     val kick = if (previous.isEmpty) 0.0 else math.max(0.0, OnsetDetector.kickPower(spectrum) - OnsetDetector.kickPower(previous))
-    val onset = Onset(flux, bassFlux, trebleFlux, rms, kick, OnsetDetector.pitch(magnitudes, window))
-    (OnsetDetector(window, samples, spectrum.toVector), onset)
+    val heard = (chromaHistory ++ hop).takeRight(OnsetDetector.ChromaWindow)
+    val onset = Onset(flux, bassFlux, trebleFlux, rms, kick, OnsetDetector.pitch(magnitudes, window), OnsetDetector.chromaOf(heard))
+    (OnsetDetector(window, samples, spectrum.toVector, heard), onset)
   }
 }
 
@@ -45,6 +47,16 @@ object OnsetDetector {
   /** Bins of about 40-130 Hz (at 44.1 kHz with a 1024 window), where a kick drum thumps. */
   val kickBins: Range = 1 to 3
 
+  /**
+   * Samples the chroma is taken over: about 93 ms at 44.1 kHz, so bins are about 11 Hz apart. With the 1024 window
+   * they'd be 43 Hz apart, wider than a semitone below about 700 Hz, where chord roots and thirds sit.
+   */
+  val ChromaWindow: Int = 4096
+
+  /** The range the chroma covers: chord tones, above the bass and below most of the bite of vocals and cymbals. */
+  val ChromaLowHz: Double = 140.0
+  val ChromaHighHz: Double = 2000.0
+
   /** Power in the kick drum's range, from the compressed spectrum. */
   private def kickPower(spectrum: scala.collection.IndexedSeq[Double]): Double =
     kickBins.filter(_ < spectrum.size).map { k =>
@@ -73,6 +85,17 @@ object OnsetDetector {
     Option.when(weight > 1e-6)(sum / weight)
   }
 
+  /** [[chroma]] of the latest `ChromaWindow` samples, padded with silence until there are that many, Hann windowed. */
+  def chromaOf(samples: Vector[Float], sampleRate: Double = 44100): Vector[Double] = ???
+
+  /**
+   * How loud each of the twelve pitch classes is between `ChromaLowHz` and `ChromaHighHz`, from C, summing to 1. Each
+   * bin's power is spread over the pitch classes its band (centre plus or minus half a bin) covers, in proportion to
+   * the overlap in log frequency, so a bin wider than a semitone doesn't put it all in one class. Empty when there's
+   * next to nothing there.
+   */
+  def chroma(magnitudes: Array[Double], window: Int, sampleRate: Double = 44100): Vector[Double] = ???
+
   private val windows = scala.collection.concurrent.TrieMap.empty[Int, Array[Double]]
 
   def hann(n: Int): Array[Double] =
diff --git a/src/main/scala/beat/TrackerSettings.scala b/src/main/scala/beat/TrackerSettings.scala
index 773b941..c719dec 100644
--- a/src/main/scala/beat/TrackerSettings.scala
+++ b/src/main/scala/beat/TrackerSettings.scala
@@ -3,6 +3,7 @@ package beat
 import domain.config.{LightShowConfig, RhythmSettings}
 import scala.concurrent.duration.*
 
+/** `leadMs` is how far ahead of what's heard the lights play (`audio.offset-ms`), so bar-line changes land ahead of them. */
 case class TrackerSettings(
   sampleRate: Double = 44100,
   hop: Int = 512,
@@ -20,7 +21,8 @@ case class TrackerSettings(
   holdSeconds: Double = 10.0,
   hitRatio: Double = 1.15,
   sparkleRatio: Double = 4.0,
-  rhythm: RhythmSettings = RhythmSettings()
+  rhythm: RhythmSettings = RhythmSettings(),
+  leadMs: Long = 0
 ) {
   val frameRate: Double = sampleRate / hop
   val hopDuration: FiniteDuration = (hop / sampleRate).seconds.toNanos.nanos
@@ -44,6 +46,7 @@ object TrackerSettings {
       holdSeconds = show.audio.holdSeconds,
       hitRatio = show.reactive.hitRatio,
       sparkleRatio = show.reactive.sparkleRatio,
-      rhythm = show.reactive.rhythm
+      rhythm = show.reactive.rhythm,
+      leadMs = show.audio.offsetMs
     )
 }
diff --git a/src/main/scala/domain/engine/Effects.scala b/src/main/scala/domain/engine/Effects.scala
index e4d92cf..7da4045 100644
--- a/src/main/scala/domain/engine/Effects.scala
+++ b/src/main/scala/domain/engine/Effects.scala
@@ -1,6 +1,6 @@
 package domain.engine
 
-import beat.AudioReading
+import beat.{AudioReading, BarAccent}
 import domain.config.{PulseSettings, ReactiveSettings, SwellSettings}
 import scala.concurrent.duration.FiniteDuration
 
@@ -9,7 +9,8 @@ import scala.concurrent.duration.FiniteDuration
  * across every light after a big hit, and a twinkle on one light after a treble onset. With live audio, each light
  * also has a role: `rhythm` is where the show is in the bar's kick pattern, `paletteShift` how many palette colours
  * the colour lights have moved along the palette, and `swell` how they swell. `rhythmIntensity`, when there is one,
- * is the rhythm lights' brightness in place of `intensity`.
+ * is the rhythm lights' brightness in place of `intensity`. `accent` is the beat that starts the bar, if the harmony
+ * shows one, and `accentStrength` how far the rest of the bar's rhythm hits and pulses are dimmed.
  */
 case class Effects(
   intensity: Double = 1.0,
@@ -20,7 +21,9 @@ case class Effects(
   rhythm: Option[Rhythm] = None,
   paletteShift: Double = 0.0,
   swell: SwellSettings = SwellSettings(),
-  rhythmIntensity: Option[Double] = None
+  rhythmIntensity: Option[Double] = None,
+  accent: BarAccent = BarAccent.Off,
+  accentStrength: Double = 0.0
 ) {
 
   /** The given light's role; without live audio every light pulses. */
@@ -33,10 +36,20 @@ case class Effects(
   /** A rhythm light's brightness multiplier `pulses` pulses in: the kick pattern, or every pulse until there's a live beat. */
   def rhythmLight(pulses: Double, settings: PulseSettings): Double =
     Pulse.brightness(
-      rhythm.fold(Pulse.onBeat(pulses, settings))(r => Pulse.rhythm(r.groove, r.beats, settings)),
+      rhythm.fold(Pulse.onBeat(pulses, settings))(r => Pulse.rhythm(r.groove, r.beats, settings, accent, accentStrength)),
       settings,
       rhythmIntensity.getOrElse(intensity)
     )
+
+  /**
+   * A pulse light's brightness multiplier `pulses` pulses in, peaking `lag` pulses after light 0. Which beat of the bar
+   * the pulse is on comes from the count since locking on (`rhythm.beats - lag`), since `pulses` starts again with each
+   * estimate of the beat; off the downbeat its peak boost is scaled by `1 - accentStrength`.
+   */
+  def pulseLight(lag: Double, pulses: Double, settings: PulseSettings): Double = ???
+
+  /** These effects when the show pulses once every `beatsPerPulse` beats: the accent only plays when every beat pulses. */
+  def forPulsesOf(beatsPerPulse: Double): Effects = ???
 }
 
 object Effects {
@@ -67,6 +80,6 @@ object Effects {
     val rhythm = reading.beat.filter(_ => reading.groove.nonEmpty).map(b => Rhythm(reading.groove, b.countAt(now)))
     val rhythmIntensity = reading.rhythmEnergy.map(e => math.max(settings.quietest, math.min(settings.loudest, e)))
     Effects(intensity, hueShift, if (flash > 0.005) flash else 0.0, sparkle, roles, rhythm, reading.pitch * settings.colourReach,
-      settings.colourSwell, rhythmIntensity)
+      settings.colourSwell, rhythmIntensity, reading.accent, settings.downbeatAccent)
   }
 }
diff --git a/src/main/scala/domain/engine/Pulse.scala b/src/main/scala/domain/engine/Pulse.scala
index be749ef..dadeb21 100644
--- a/src/main/scala/domain/engine/Pulse.scala
+++ b/src/main/scala/domain/engine/Pulse.scala
@@ -1,6 +1,6 @@
 package domain.engine
 
-import beat.Groove
+import beat.{BarAccent, Groove}
 import domain.config.PulseSettings
 
 object Pulse {
@@ -20,6 +20,9 @@ object Pulse {
    */
   def onBeat(phase: Double, s: PulseSettings): Double = level(phase + s.attack, s)
 
+  /** The pulse [[onBeat]] is playing `phase` pulses in: the one that lands next while rising, the last one after. */
+  def pulseAt(phase: Double, s: PulseSettings): Long = ???
+
   /**
    * Envelope level `beats` beats into the beat grid for a light following the bar's kick pattern: each sixteenth of
    * the bar gets its own pulse, as strong as the groove's weight there and timed like [[onBeat]], so its peak lands on
@@ -35,6 +38,18 @@ object Pulse {
     }
   }
 
+  /** [[rhythm]], dimmed away from the downbeat by [[accentFactor]]. */
+  def rhythm(groove: Vector[Double], beats: Double, s: PulseSettings, accent: BarAccent, strength: Double): Double =
+    rhythm(groove, beats, s) * accentFactor(groove, beats, accent, strength)
+
+  /**
+   * How much of the rhythm envelope plays `beats` beats into the grid: all of it while the accent is off at the beat,
+   * or while `beats` is nearer the first sixteenth of a downbeat than any other sixteenth with a groove weight above 0,
+   * the groove repeating bar on bar; otherwise `1 - strength`. So the downbeat's hit peaks as it would without the
+   * accent, and every other hit, with whatever is still fading under it, at `1 - strength` of that.
+   */
+  def accentFactor(groove: Vector[Double], beats: Double, accent: BarAccent, strength: Double): Double = ???
+
   /**
    * Brightness multiplier for an envelope level: `restLevel` at rest, `restLevel + peakBoost` at the peak, all scaled
    * by the music's `intensity` (1 when the show isn't reacting to live audio).
diff --git a/ui/src/main/scala/ui/Sections.scala b/ui/src/main/scala/ui/Sections.scala
index 9b74838..954a557 100644
--- a/ui/src/main/scala/ui/Sections.scala
+++ b/ui/src/main/scala/ui/Sections.scala
@@ -128,6 +128,14 @@ object Sections {
         min = 0, max = 0.9, step = 0.05,
         get = _.reactive.rhythm.smoothing,
         set = rhythm((r, v) => r.copy(smoothing = v))
+      ),
+      Slider(
+        key = "reactive.downbeat-accent",
+        label = "Downbeat accent",
+        help = "Once the harmony shows where the bar starts, dims every other rhythm hit in the bar and the pulses on beats two to four by this much. Only when every beat pulses. 0 switches it off.",
+        min = 0, max = 1, step = 0.05,
+        get = _.reactive.downbeatAccent,
+        set = reactive((r, v) => r.copy(downbeatAccent = v))
       )
     ),
     unused = unlessPlaying(Set(LightRole.Rhythm, LightRole.Pulse), "No light is a rhythm or pulse light")
```

## Slices

1. **Accent the downbeat on rhythm and pulse lights from the harmony's bar position.** In one PR: the chroma from a longer window, the Downbeat search with exact on, move and off rules, BarAccent landing on a bar line ahead of the lights, pulse lights placed by the count since lock, the rhythm envelope dimmed outside the downbeat's cell, the beats-per-pulse gate, the reactive.downbeat-accent setting (config, validation, slider), the accent log lines, and the four docs/how-it-works.md passages.
   - On generated drums with chord tones between 150 and 500 Hz changing every four beats, once about 8 bars have played, the pulse lights peak brighter on the bar's first beat than on the other three. On the same audio, the rhythm lights' hit on the first sixteenth is brighter than every other hit in the bar, off-beat sixteenths included.
   - With the accent on, beat one (the first sixteenth for rhythm lights) peaks exactly as at strength 0. Every other rhythm hit and every pulse on beats 2–4 peaks lower, and rest brightness, hue and saturation match strength 0.
   - The accent doesn't show before the 8th bar line after the lock.
   - With chords changing every beat (alternating two chords with no shared notes), never changing, or changing at shuffled bar positions (no position more than 3 times in any 8 bars), every pulse and rhythm hit matches strength 0.
   - Once the accent is on, a single bar that breaks the pattern (a fill, or a bar of 2) doesn't switch it off or move it.
   - When the harmony's position moves to another beat and stays there, the accent moves after 4 bars, not after 1.
   - When the harmony stops showing a position, the accent is off from the bar line 8 bars after the first bar that missed it.
   - Every switch on, move and switch off lands on a bar line of the slot it lands on, at the default and at a 250 ms audio.offset-ms.
   - After the tracker loses the beat and locks again, the accent is off until a position has held for about 8 bars.
   - Pulse lights accent the same beat while the beat estimate's beatAt and number move between frames and the count since lock stays the same.
   - At strength 0 the output for any audio is identical to today's. At strength 1 with the accent on, every rhythm hit but the bar's first sixteenth, and every pulse on beats 2–4, peaks at rest brightness, while beat one peaks at today's peak.
   - With beats-per-pulse above 1, with Spotify timing, or with reactive off, the output is identical to today's at any strength. Colour lights are unchanged at any strength.
   - Without the setting the app uses 0.3. Below 0, above 1 or NaN, the app refuses to start and reports "reactive.downbeat-accent must be between 0 and 1".
   - The settings page shows "Downbeat accent" in Rhythm and pulse lights, and changing it there changes the show.
   - docs/how-it-works.md, light-roles section: the downbeat accent accents the rhythm lights' hit on the bar's first sixteenth and the pulse lights' pulse on beat one by dimming the rest of the bar, and leaves the colour lights alone.
   - docs/how-it-works.md, pulse section: the accent dims every other rhythm hit in the bar and the pulses on beats 2–4. It switches on after about 8 bars with one consistent position, moves after 4 bars at a new position, ignores a single odd bar, and switches off once 8 bars have passed without a consistent position. Changes always land on a bar line, and a new lock starts it again with the accent off.
   - docs/how-it-works.md config table: reactive.downbeat-accent, range 0 to 1, default 0.3.
   - docs/how-it-works.md beats-per-pulse note: with beats-per-pulse above 1 the show is unchanged by the accent.
   - Tests: - **DownbeatSpec** (tables of synthetic chroma per beat through `Downbeat.at`): on at the 8th bar line and not the 7th; one odd bar (a fill, or a bar of 2) keeps the slot; a move after 4 bars and not 3; off with `fromBeat == missed + 32` exactly; `start` gives Off. Properties: `landing(slot, after)` is ≡ slot (mod 4), > after and ≤ after + 4; every `fromBeat` is > count + lead for leads from −0.5 to 3.5 beats; `vote` of four equal changes, or none, is None; `change` is 0 for equal or empty chromas.
- **OnsetDetectorSpec**: tones at 165, 196, 247 and 330 Hz each peak at their own pitch class; a C-major and an F#-major triad in 150–500 Hz have a cosine change above 0.5; silence gives empty.
- **BarAccentSpec / PulseSpec** (ScalaCheck over grooves, strengths and accents): with the accent off or strength 0, the overload equals `rhythm`. At the downbeat's peak it equals today's. At every other weighted sixteenth's peak it's (1 − strength) × today's, so 0 at strength 1. `pulsePeak` is 1 on the downbeat and 1 − strength elsewhere.
- **EffectsSpec**: `pulseLight` matches today's with no accent. A table moves `beatAt`/`number` between frames with `countAt` fixed and gets the same accented beat. `forPulsesOf(b ≠ 1)` drops the accent.
- **SyncEnginePulseSpec**: properties show hue, saturation and the colour branch unchanged.
- **RhythmLightsSpec / BeatTrackerSpec** end to end: Synth drums mixed with a new `Synth.chords` (sines at 150–500 Hz) in the four layouts, with per-bar peaks compared against strength 0, plus a silence gap and relock.
- **SyncEngineRunSpec** under TestControl: audio offset 250 ms with a scripted reading stream; the first dimmed and the first undimmed frame fall at `countAt` ≥ `fromBeat`, on a bar line. Also covers beats-per-pulse 2 and Spotify timing.
- **SyncEngineLogSpec**: `accentNews` gives the on, move and off lines with their fields.
- **LightShowConfigSpec**: the default and a table of out-of-range values. **SettingsJsonSpec**: the field round-trips. **LiveSettingsSpec**: `TrackerSettings.from` copies `offsetMs` into `leadMs`. **SectionsSpec** (Node): the slider sits in rhythmAndPulse and its `set` changes `downbeatAccent`.

## Alternatives

- Finding the bar from the bass or kick: the analysis showed it's never consistent, and it's out of scope.
- Chroma from the 1024-sample window: bins of 43 Hz put roots and thirds below 700 Hz in the wrong pitch class. Starting the range at 700 Hz would drop them altogether.
- Dimming each sixteenth's own pulse only, and leaving beat one's tail: at strength 1 the tail keeps the next hits above rest (criterion 12), and at lower strengths it can hide the dimming (criterion 3).