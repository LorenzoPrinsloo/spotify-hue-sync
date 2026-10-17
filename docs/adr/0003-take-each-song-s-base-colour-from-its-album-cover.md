# ADR 0003: Take each song's base colour from its album cover

Issue: LorenzoPrinsloo/spotify-hue-sync#10 · Status: proposed

## Context

`PlaybackState` decodes the album cover URL, but nothing uses it. Every song's palette starts from `RGB.random`. The decoder also requires a cover, so a local file or an episode without `album.images` makes every poll fail. Issue #10 adds `light-show.album-cover-colours`, on by default. When it's on, a song's palette is built from the most vivid colour that covers about 5% or more of the cover. The lights wait at most 1 s for that colour, use a random palette for the whole song otherwise, and remember each cover's colour by URL.

## Decision

One PR, split into a pure part and an I/O part. `TrackShow` records the base colour.

1. **Pure rule (`CoverColour.base`)**, using named constants. A pixel is *vivid* when its HSB saturation ≥ `MinSaturation = 0.35` and its brightness ≥ `MinBrightness = 0.25`. All other pixels (black, white, grey, muddy) count only towards the total. A pixel's *vividness* is saturation × brightness. Vivid pixels go into `HueBins = 36` bins of 10° each, where bin k covers [10k°, 10k+10°). For each bin k, a *window* is bins k−1, k and k+1, with indices taken mod 36, so it wraps through 0°/360°. A window qualifies when its pixels make up at least `MinShare = 0.05` of all pixels. Among qualifying windows, the one with the highest mean vividness wins, with a larger share breaking ties. The result is that window's circular-mean hue, with the mean saturation and brightness, built through `Hsb.toRgb`. None means no window qualifies. A 6% red patch whose noise spans bins 35 and 0 still qualifies through the window centred on bin 0. A window is only 30° wide, so distinct hues aren't averaged into mud.
2. **`Palette.from(base, type)`** builds every type from a given base. `random(t)` becomes `RGB.random.flatMap(from(_, t))`, so palettes for a given seed don't change.
3. **I/O (`AlbumCovers.fromHttp`)**: GET the cover with the existing Spotify client. Decode in `Sync.interruptible` (`ImageIO.read`), downsample to at most 64×64, then run `CoverColour.base`. Results, including None, are cached in a `Ref[Map[url, Option[RGB]]]`. Failures aren't cached. Errors are `CoverError`.
4. **Engine**: `showFor(trackId, cover)` runs the tempo lookup and `coverBase` with `parTupled`. `coverBase` *starts* the lookup as a fiber and waits on its join with `timeoutTo(CoverWait = 1.s)`. On timeout it cancels the fiber in the background (`fiber.cancel.start`) and doesn't wait for it, so the frame loop never waits more than 1 s, even mid-decode. A timeout or any failure logs one WARN and gives a random base. The show is fixed per track, so a cover that arrives late never changes it. `applyNow` builds the new type with `Palette.from(show.base, t)`. With the setting off or no URL, the base is `RGB.random(using random)`, exactly as today.

`make` takes `covers: Option[AlbumCovers[F]] = None` and resolves `AlbumCovers.none[F]` in the body. A default argument can't see the `Async` bound, which is what broke the last skeleton.

## Components and call direction

- `domain.spotify.PlaybackState`: `albumCoverUrl: Option[String]`. The decoder reads `item.album.images[0].url` optionally.
- `domain.engine.CoverColour` (new, pure): pixels → `Option[RGB]`, with the constants above.
- `domain.engine.Palette`: `from(base, mode)`. `random` is defined through it.
- `client.CoverError` (new, sealed). `client.AlbumCovers` (new): trait, `none`, `fromHttp`, `pixels`.
- `SyncEngine`: `playingFrame` → `showFor(id, state.albumCoverUrl)` → (`spotify.getTempo` ∥ `coverBase` → `AlbumCovers.baseColour`) → `Palette.from`. `applyNow` → `Palette.from(show.base, …)`. `TrackShow` gains `base`.
- `Main`: `AlbumCovers.fromHttp(clients.spotify)` → `SyncEngine.make(…, covers = Some(_))`.
- `model` `LightShowConfig`: `albumCoverColours: Boolean = true`. `reference.conf`: `album-cover-colours = true`.
- `ui`: `Control.AlbumCoverColours` placed after `Control.Palette` in `Sections.allLights`. `SettingsPage` renders it as a checkbox.
- Docs: a cover-colour section in `how-it-works.md`; the roadmap item removed; `AlbumCovers` added to `architecture.md`.

Calls go one way: SyncEngine → AlbumCovers → (http4s Client, ImageIO, CoverColour).

## Contracts

- Config `light-show.album-cover-colours` (Boolean, default true): additive.
- `LightShowConfig` (shared model, JVM and JS): new field appended last with a default, so additive. `ConfigSnippet` emits `album-cover-colours = false` only when the value differs from the default.
- Settings JSON (`/api/settings`, `SettingsView`): each config object gains `"albumCoverColours"`. Responses change additively. A POST that leaves the field out is rejected by the derived codec, but the page and the server ship from one build.
- `PlaybackState.albumCoverUrl` changes from `String` to `Option[String]`. Not additive, but internal: its only users are `SyncEngine` and `PlaybackStateSpec`. The decoder becomes more lenient.
- `TrackShow.base: RGB`: internal, built only by `SyncEngine`.
- `SyncEngine.showFor(…, cover = None)` and `make(…, covers = None)`: new parameters with defaults, so additive.
- `GET /api/palette`: unchanged in shape.
- Outbound: one GET per new cover URL to Spotify's image CDN, with no credentials.
- No environment variables.

## Failure modes

Errors are the sealed `client.CoverError extends RuntimeException`: `Refused(url, status)`, `Unreachable(url, error)`, `Unreadable(url)` (ImageIO returns null or throws, or there are no pixels) and `TimedOut(url, after)`. `fromHttp` maps every failure it meets into one of these, so nothing escapes unwrapped.
- **Fetch or read (AlbumCovers)**: all recoverable. `coverBase` recovers any `CoverError`, or any other non-fatal throwable, into a random base with one WARN. Failures aren't cached, so a later track retries.
- **No vivid colour** isn't an error: `Right(None)` is cached and gives a random base with no WARN.
- **1 s wait (engine)**: on timeout, `TimedOut` is recovered the same way. The cover fiber is cancelled in the background without being waited on, and the interruptible decode stops at its next interrupt check. A read that finishes regardless may land in the cache, which only helps the next track. The current show never changes.
- **No cover / setting off**: nothing is fetched, the base is random and nothing is logged.
- **Playback decode**: a missing cover is no longer an error. Other decode failures are logged as ERROR, as today.
- **Startup**: a non-boolean value is a pureconfig failure, and the app refuses to start.

## Config

`light-show.album-cover-colours = true` (Boolean), the default in both `reference.conf` and the case class. pureconfig's Boolean reader rejects anything else at startup. `LightShowConfig.problems` is unchanged, since every Boolean is valid. The value is editable live and taken up at the next song via `live.startSong`. These are constants, not settings, because the issue fixes them: `SyncEngine.CoverWait = 1.second`, and in `CoverColour`, `MinShare = 0.05`, `MinSaturation = 0.35`, `MinBrightness = 0.25`, `HueBins = 36` and `WindowBins = 1` (neighbours on each side). `AlbumCovers.MaxSide = 64`.

## Observability

- WARN, once per song, when the cover fails or times out: "Couldn't take the colours from the album cover for <trackId>, using a random palette: <message>". Fields: `track_id`, `cover_url`, `reason` (`refused` | `unreachable` | `unreadable` | `timed_out` | `failed`), plus `status` (Refused) and `waited_ms` (TimedOut).
- INFO: the existing "New show for <trackId>…" line gains "base #rrggbb from the album cover" or "random base #rrggbb".
- DEBUG in `AlbumCovers` after a read: `cover_url`, `base` (hex or none), `pixels`.
- Image bytes and anything from `.env` are never logged.

## Program design

`src/main/scala/domain/engine/CoverColour.scala` (new):

```scala
package domain.engine

/**
 * How a song's base colour is read from its album cover's pixels. Vivid pixels are grouped by hue into `HueBins` bins;
 * a bin counts together with `WindowBins` neighbours on each side, wrapping round 0°/360°, so a colour lying across a
 * bin edge isn't split below `MinShare`.
 */
object CoverColour {

  /** The least share of all the cover's pixels a colour has to cover to be its base colour. */
  val MinShare: Double = 0.05

  /** Pixels less saturated than this are black, white or grey. */
  val MinSaturation: Double = 0.35

  /** Pixels darker than this are black. */
  val MinBrightness: Double = 0.25

  /** Hue bins round the colour wheel, each 360 / `HueBins` degrees wide; bin k starts at k * 360 / `HueBins`. */
  val HueBins: Int = 36

  /** Neighbouring bins on each side counted with a bin. */
  val WindowBins: Int = 1

  /** Whether a pixel is a colour rather than black, white or grey. */
  def isVivid(c: RGB): Boolean = ???

  /** Saturation times brightness: how vivid a colour is. */
  def vividness(c: RGB): Double = ???

  /** The hue bin a colour falls in, from 0 to `HueBins` - 1. */
  def hueBin(c: RGB): Int = ???

  /**
   * The mean colour of the most vivid window of bins covering at least `MinShare` of the pixels, or None when there's
   * no such window.
   */
  def base(pixels: Seq[RGB]): Option[RGB] = ???
}
```

`src/main/scala/client/CoverError.scala` (new):

```scala
package client

import org.http4s.Status

import scala.concurrent.duration.FiniteDuration

/** Why an album cover's colour couldn't be read. Each one leaves the song with a random palette. */
sealed abstract class CoverError(message: String) extends RuntimeException(message) {

  /** The case's name in the log's `reason` field. */
  def reason: String = ???
}

object CoverError {
  final case class Refused(url: String, status: Status) extends CoverError(s"The album cover at $url answered $status")
  final case class Unreachable(url: String, error: Throwable) extends CoverError(s"Couldn't reach the album cover at $url: $error")
  final case class Unreadable(url: String) extends CoverError(s"The album cover at $url isn't an image that can be read")
  final case class TimedOut(url: String, after: FiniteDuration) extends CoverError(s"The album cover at $url didn't arrive within $after")
}
```

`src/main/scala/client/AlbumCovers.scala` (new):

```scala
package client

import cats.Applicative
import cats.effect.{Async, Sync}
import domain.engine.RGB
import org.http4s.client.Client

/** Each album cover's base colour, read once per cover URL and remembered until the app stops. */
trait AlbumCovers[F[_]] {

  /** The cover's base colour, or None when it has no vivid colour. Raises a `CoverError` when it can't be fetched or read. */
  def baseColour(url: String): F[Option[RGB]]
}

object AlbumCovers {

  /** The longest side, in pixels, a cover is scaled down to before it's read. */
  val MaxSide: Int = 64

  /** Never has a colour, so every song gets a random palette. */
  def none[F[_]: Applicative]: AlbumCovers[F] = ???

  /** Fetches covers with `client`, remembering each one read by its URL; failures aren't remembered. */
  def fromHttp[F[_]: Async](client: Client[F]): F[AlbumCovers[F]] = ???

  /**
   * The image's pixels, scaled down to at most `MaxSide` by `MaxSide`. Decoding can be interrupted. Raises
   * `CoverError.Unreadable` if `bytes` isn't an image.
   */
  def pixels[F[_]: Sync](url: String, bytes: Array[Byte]): F[Vector[RGB]] = ???
}
```

Changes to existing files:

```diff
diff --git a/model/src/main/scala/domain/config/LightShowConfig.scala b/model/src/main/scala/domain/config/LightShowConfig.scala
index 2e69f3f..e676df4 100644
--- a/model/src/main/scala/domain/config/LightShowConfig.scala
+++ b/model/src/main/scala/domain/config/LightShowConfig.scala
@@ -5,7 +5,8 @@ import io.circe.Codec
 
 /**
  * The light show's settings. `area` is the entertainment area to play on, by name; without one, the app uses
- * `hue.zone-id`, or the bridge's only music area.
+ * `hue.zone-id`, or the bridge's only music area. `albumCoverColours` builds each song's palette from its album cover's
+ * colour rather than a random one.
  */
 case class LightShowConfig(
   palette: PaletteType = PaletteType.Analogous,
@@ -18,7 +19,8 @@ case class LightShowConfig(
   frameIntervalMs: Long = 16,
   pulse: PulseSettings = PulseSettings(),
   pause: PauseSettings = PauseSettings(),
-  area: Option[String] = None
+  area: Option[String] = None,
+  albumCoverColours: Boolean = true
 ) derives Codec.AsObject
 
 object LightShowConfig {
diff --git a/src/main/scala/Main.scala b/src/main/scala/Main.scala
index 5c5e8a1..2d174da 100644
--- a/src/main/scala/Main.scala
+++ b/src/main/scala/Main.scala
@@ -2,7 +2,7 @@ import cats.effect.*
 import cats.effect.{ExitCode, IOApp}
 import cats.effect.kernel.Resource
 import cats.effect.std.Env
-import client.{DtlsEmitter, EntertainmentStream, HttpClients, HueClient, SpotifyClient, TokenFile, TokenStore}
+import client.{AlbumCovers, DtlsEmitter, EntertainmentStream, HttpClients, HueClient, SpotifyClient, TokenFile, TokenStore}
 import cats.syntax.all.*
 import beat.{BeatTracking, TrackerSettings}
 import domain.config.{AppConfig, BeatSource}
@@ -50,7 +50,8 @@ object Main extends IOApp {
       _ <- Resource.eval(logger.info(
           s"Listening for the beat on '${config.lightShow.audio.device}'. If it never locks on, check that music is playing through it and that your terminal has microphone access."
         ).whenA(config.lightShow.beatSource == BeatSource.Audio))
-      engine <- Resource.eval(SyncEngine.make[IO](config, live, spotify, Zone(area.id, channels), lights, audio = audio))
+      covers <- Resource.eval(AlbumCovers.fromHttp[IO](clients.spotify))
+      engine <- Resource.eval(SyncEngine.make[IO](config, live, spotify, Zone(area.id, channels), lights, audio = audio, covers = Some(covers)))
       _ <-
         if (!config.ui.enabled) Resource.unit[IO]
         else
diff --git a/src/main/scala/SyncEngine.scala b/src/main/scala/SyncEngine.scala
index 4845563..c15f0a3 100644
--- a/src/main/scala/SyncEngine.scala
+++ b/src/main/scala/SyncEngine.scala
@@ -1,7 +1,7 @@
 import cats.effect.*
 import cats.syntax.all.*
 import beat.{AudioReading, BeatState}
-import client.{SpotifyClient, SpotifyTlsFailure}
+import client.{AlbumCovers, SpotifyClient, SpotifyTlsFailure}
 import fs2.Stream
 import domain.config.{AppConfig, LightShowConfig, PulseSettings}
 import domain.engine.{Effects, LightRole, LocalPlayback, Palette, PaletteType, Pulse, RGB, Renderer}
@@ -14,8 +14,11 @@ import scala.concurrent.duration.*
 import scala.util.Random
 import settings.LiveSettings
 
-/** The track's tempo from Spotify, if it gave one, and the palette, settings and lights the show plays it with. */
-case class TrackShow(tempo: Option[Double], palette: Palette, settings: LightShowConfig, zone: Zone) {
+/**
+ * The track's tempo from Spotify, if it gave one, and the palette, settings and lights the show plays it with. `base` is
+ * the colour the palette was built from, so a new palette type keeps it.
+ */
+case class TrackShow(tempo: Option[Double], palette: Palette, settings: LightShowConfig, zone: Zone, base: RGB) {
   def bpm: Double = tempo.getOrElse(settings.defaultBpm) / settings.beatsPerPulse
 }
 
@@ -27,14 +30,22 @@ class SyncEngine[F[_]: Async] private (
   lights: Lights[F],
   current: Ref[F, Option[(TrackId, TrackShow)]],
   random: Random,
-  audio: Stream[F, AudioReading]
+  audio: Stream[F, AudioReading],
+  covers: AlbumCovers[F]
 ):
 
   private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]
 
-  private def newPalette(paletteType: PaletteType): F[Palette] =
-    Async[F].delay(Palette.random(paletteType)(using random))
-      .flatMap(Async[F].fromOption(_, new IllegalStateException(s"Couldn't make a $paletteType palette")))
+  private def randomBase: F[RGB] = ???
+
+  private def paletteFrom(base: RGB, paletteType: PaletteType): F[Palette] = ???
+
+  /**
+   * The base colour from the song's album cover, if the settings take it from there and it's read within
+   * `SyncEngine.CoverWait`. None otherwise, logging one warning if the cover couldn't be fetched or read in time. The
+   * lookup runs on its own fiber, which is cancelled without waiting for it once the time is up.
+   */
+  private def coverBase(trackId: TrackId, cover: Option[String], settings: LightShowConfig): F[Option[RGB]] = ???
 
   /** The lights to play `settings` on: the area they name, or else the ones `before` played on (or the first zone). */
   private def zoneFor(settings: LightShowConfig, before: Option[TrackShow]): F[Zone] = {
@@ -52,48 +63,20 @@ class SyncEngine[F[_]: Async] private (
   }
 
   /**
-   * The show for a track. A new track takes up the latest settings, its area's lights and a new palette; the show
-   * then stays the same until the track changes, so changes from the settings page wait for the next song.
+   * The show for a track. A new track takes up the latest settings, its area's lights and a new palette, built from its
+   * album cover's colour when there is one; the show then stays the same until the track changes, so changes from the
+   * settings page wait for the next song.
    */
-  def showFor(trackId: TrackId): F[TrackShow] =
-    current.get.flatMap {
-      case Some((playing, show)) if playing == trackId => Async[F].pure(show)
-      case before =>
-        for {
-          settings <- live.startSong
-          tempo <- spotify.getTempo(trackId).handleErrorWith { err =>
-            (err match {
-              case _: SpotifyTlsFailure => logger.error(s"Couldn't fetch the tempo for $trackId: $err")
-              case _ => logger.warn(s"Couldn't fetch the tempo for $trackId: $err")
-            }) *> Async[F].pure(None)
-          }
-          palette <- newPalette(settings.palette)
-          lit <- zoneFor(settings, before.map(_._2))
-          created = TrackShow(tempo, palette, settings, lit)
-          _ <- current.set(Some(trackId -> created))
-          source = tempo.fold(s"default ${settings.defaultBpm} BPM")(t => s"track tempo $t BPM")
-          _ <- logger.info(s"New show for $trackId: pulsing at ${created.bpm} BPM ($source), palette ${settings.palette}:\n${palette.show}")
-        } yield created
-    }
+  def showFor(trackId: TrackId, cover: Option[String] = None): F[TrackShow] = ???
 
   /** The palette of the song playing now, if one has started. */
   def palette: F[Option[Palette]] = current.get.map(_.map(_._2.palette))
 
   /**
    * Takes up the latest settings now rather than at the next song, moving to another area if they name one. The palette
-   * only changes if its type did.
+   * only changes if its type did, and is then built from the song's base colour.
    */
-  def applyNow: F[Unit] =
-    for {
-      settings <- live.startSong
-      playing <- current.get
-      _ <- playing.traverse_ { (trackId, show) =>
-        val palette = if (show.settings.palette == settings.palette) Async[F].pure(show.palette) else newPalette(settings.palette)
-        (palette, zoneFor(settings, Some(show))).flatMapN((p, lit) =>
-          current.set(Some(trackId -> show.copy(palette = p, settings = settings, zone = lit)))
-        )
-      }
-    } yield ()
+  def applyNow: F[Unit] = ???
 
   /**
    * Keeps `audioRef` up to date with what the music is doing, logging when the beat locks on, is lost or changes
@@ -145,7 +128,7 @@ class SyncEngine[F[_]: Async] private (
    */
   private def playingFrame(local: LocalPlayback, audioRef: Ref[F, Option[AudioReading]]): F[Frame] =
     for {
-      track <- showFor(local.state.trackId)
+      track <- showFor(local.state.trackId, local.state.albumCoverUrl)
       show = track.settings
       reading <- audioRef.get
       monotonic <- Clock[F].monotonic
@@ -164,6 +147,7 @@ class SyncEngine[F[_]: Async] private (
 
 object SyncEngine:
 
+  /** `covers` reads album covers' colours; without it, every song gets a random palette. */
   def make[F[_]: Async](
     config: AppConfig,
     live: LiveSettings[F],
@@ -171,9 +155,12 @@ object SyncEngine:
     zone: Zone,
     lights: Lights[F],
     random: Random = new Random(),
-    audio: Stream[F, AudioReading] = Stream.empty
-  ): F[SyncEngine[F]] =
-    Ref.of[F, Option[(TrackId, TrackShow)]](None).map(new SyncEngine[F](config, live, zone, spotify, lights, _, random, audio))
+    audio: Stream[F, AudioReading] = Stream.empty,
+    covers: Option[AlbumCovers[F]] = None
+  ): F[SyncEngine[F]] = ???
+
+  /** How long a song's start waits for its album cover before playing a random palette. */
+  val CoverWait: FiniteDuration = 1.second
 
   /** How far the tempo has to move from the last one logged before it's logged again. */
   private val TempoChangeToLog: Double = 0.03
diff --git a/src/main/scala/domain/engine/Palette.scala b/src/main/scala/domain/engine/Palette.scala
index 7d21c2c..673da52 100644
--- a/src/main/scala/domain/engine/Palette.scala
+++ b/src/main/scala/domain/engine/Palette.scala
@@ -13,35 +13,10 @@ object Palette {
     p.colors.map(_.show).mkString("\n")
   }
 
-  def random(mode: PaletteType)(using rnd: Random = Random): Option[Palette] = mode match {
-    case Complementary => {
-      for {
-        base <- RGB.random
-        compliment <- base.compliment
-      } yield Palette(colors = Vector(base, compliment))
-    }
-    case Analogous => {
-      RGB.random.map { base =>
-        Palette(colors = base.analogous.toVector)
-      }
-    }
-    case Monochromatic =>
-      RGB.random.map(base => Palette(base.monochromatic.toVector))
-    case Triadic =>
-      RGB.random.map(base => Palette(base.triadic.toVector))
-    case Tetradic =>
-      RGB.random.map(base => Palette(base.tetradic.toVector))
-    case AnalogousComplementary(order) => {
-      for {
-        base <- RGB.random
-        compliment <- base.compliment
-        orderedColors = order match {
-          case Flat => (base.analogous ++ compliment.analogous).toVector
-          case Interspersed => Vector(base.analogous, compliment.analogous).transpose.flatten
-        }
-      } yield Palette(colors = orderedColors)
-    }
-  }
+  def random(mode: PaletteType)(using rnd: Random = Random): Option[Palette] = ???
+
+  /** A palette of type `mode` built from `base`, as `random` builds one from a random base. */
+  def from(base: RGB, mode: PaletteType): Option[Palette] = ???
 
   extension (pal: Palette) {
 
diff --git a/src/main/scala/domain/spotify/PlaybackState.scala b/src/main/scala/domain/spotify/PlaybackState.scala
index 1348d06..ea9ba2d 100644
--- a/src/main/scala/domain/spotify/PlaybackState.scala
+++ b/src/main/scala/domain/spotify/PlaybackState.scala
@@ -5,21 +5,15 @@ import io.circe.Decoder
 
 import scala.concurrent.duration.{DurationLong, FiniteDuration}
 
+/** `albumCoverUrl` is the largest cover Spotify lists, or None for an item without one (a local file, an episode). */
 case class PlaybackState(
   trackId: TrackId,
   progress: FiniteDuration,
   isPlaying: Boolean,
-  albumCoverUrl: String
+  albumCoverUrl: Option[String]
 )
 object PlaybackState {
   given Decoder[PlaybackState] = Decoder.instance { cursor =>
-    val item = cursor.downField("item")
-
-    for {
-      trackId <- item.downField("id").as[String]
-      progress <- cursor.downField("progress_ms").as[Long]
-      isPlaying <- cursor.downField("is_playing").as[Boolean]
-      albumCoverUrl <- item.downField("album").downField("images").downArray.downField("url").as[String]
-    } yield PlaybackState(trackId = TrackId(trackId), progress = progress.millis, isPlaying = isPlaying, albumCoverUrl = albumCoverUrl)
+    ???
   }
 }
diff --git a/src/test/scala/domain/spotify/PlaybackStateSpec.scala b/src/test/scala/domain/spotify/PlaybackStateSpec.scala
index 54676e2..ec8dad4 100644
--- a/src/test/scala/domain/spotify/PlaybackStateSpec.scala
+++ b/src/test/scala/domain/spotify/PlaybackStateSpec.scala
@@ -20,7 +20,7 @@ class PlaybackStateSpec extends munit.FunSuite {
     assertEquals(state.trackId.value, "4uLU6hMCjMI75M1A2tKUQC")
     assertEquals(state.progress, 61500.millis)
     assert(state.isPlaying)
-    assertEquals(state.albumCoverUrl, "https://i.scdn.co/image/large")
+    assertEquals(state.albumCoverUrl, Some("https://i.scdn.co/image/large"))
   }
 
   test("fails when nothing is playing and the response has no track") {
diff --git a/ui/src/main/scala/ui/Control.scala b/ui/src/main/scala/ui/Control.scala
index 23b73ed..a4d1392 100644
--- a/ui/src/main/scala/ui/Control.scala
+++ b/ui/src/main/scala/ui/Control.scala
@@ -2,7 +2,7 @@ package ui
 
 /** Settings in a section that aren't sliders, each with its own control, shown before the section's sliders. */
 enum Control derives CanEqual {
-  case Area, Roles, Reactions, Palette, SongPalette, RestingColour, FixedSettings
+  case Area, Roles, Reactions, Palette, AlbumCoverColours, SongPalette, RestingColour, FixedSettings
 
   /** The settings this control shows, by their path in the config file. */
   def keys: List[String] = this match {
@@ -10,6 +10,7 @@ enum Control derives CanEqual {
     case Roles => List("reactive.roles")
     case Reactions => List("reactive.enabled")
     case Palette => List("palette")
+    case AlbumCoverColours => List("album-cover-colours")
     case SongPalette => Nil
     case RestingColour => List("pause.resting-colour")
     case FixedSettings => List("beat-source", "audio.device", "frame-interval-ms")
diff --git a/ui/src/main/scala/ui/Sections.scala b/ui/src/main/scala/ui/Sections.scala
index d939ade..bc7df46 100644
--- a/ui/src/main/scala/ui/Sections.scala
+++ b/ui/src/main/scala/ui/Sections.scala
@@ -235,7 +235,7 @@ object Sections {
   val allLights: Section = Section(
     title = "All lights",
     intro = "How brightness and flashes follow the music, the colours each song gets, and what the lights do while nothing plays.",
-    controls = List(Control.Palette, Control.SongPalette, Control.RestingColour),
+    controls = List(Control.Palette, Control.AlbumCoverColours, Control.SongPalette, Control.RestingColour),
     sliders = List(
       Slider(
         key = "reactive.quietest",
diff --git a/ui/src/main/scala/ui/SettingsPage.scala b/ui/src/main/scala/ui/SettingsPage.scala
index c70ecd0..2f4f4a3 100644
--- a/ui/src/main/scala/ui/SettingsPage.scala
+++ b/ui/src/main/scala/ui/SettingsPage.scala
@@ -109,6 +109,7 @@ final class SettingsPage {
     case Control.Roles => roles
     case Control.Reactions => reactionsSwitch
     case Control.Palette => paletteChoice
+    case Control.AlbumCoverColours => albumCoverSwitch
     case Control.SongPalette => songColours
     case Control.RestingColour => restingColour
     case Control.FixedSettings => fixedSettings
@@ -194,6 +195,9 @@ final class SettingsPage {
       p(cls := "help", "Each new song gets a new palette of this kind.")
     )
 
+  /** The `album-cover-colours` checkbox: build each song's palette from its album cover's colour. */
+  private def albumCoverSwitch: HtmlElement = ???
+
   private def songColours: HtmlElement =
     div(
       cls := "field",
```

## Slices

1. **Base colour from the album cover, with an optional cover and the album-cover-colours setting.** Songs whose cover has a vivid colour covering at least 5% of it get a palette built from that colour. Every other song gets a random palette as today: no cover, a failed or slow cover, or the setting off. A playback state without a cover no longer fails. The setting is on the settings page, covers are remembered by URL, and the docs are updated.
   - A song whose cover is mostly vivid red (hue near 0°, including pixels at 355° and 5°) gets a `TrackShow.base` with a red hue. A song whose cover is mostly vivid blue gets a blue base. Each palette entry sits at the place its type puts the base (for Analogous, the middle entry has the base's hue).
   - `GET /api/palette` returns that show's colours in today's shape.
   - A cover that is black, white and grey apart from a vivid patch under 5% gives a random base. A mostly grey cover with a vivid patch just over 5% gives a base with that patch's hue, even when the patch's hues lie across a bin edge, including red across 0°/360°.
   - A cover that answers 500, can't be reached, or returns bytes that aren't an image leaves the song playing with a random base. Exactly one WARN is logged for that song.
   - A cover that takes 2 s gives a random base and one WARN with reason timed_out. The show's palette is the same at 5 s as at the song's start, and the first frame arrives within 1 s of the song starting.
   - A second track with the same cover URL gets the cover-derived base, and the cover server receives only one request.
   - A playback state with no album, or with an empty images list, decodes without error. The song plays with a random base, no cover request is made and no WARN is logged.
   - After "Apply now" with a different palette type mid-song, the palette equals `Palette.from(base, newType)` for the base the song started with, both for a cover-derived base and for a random one.
   - With album-cover-colours off, no cover request is made, and for a given seed the palettes equal what today's code produces.
   - With no light-show.album-cover-colours in the config, the setting loads as true. The settings page's All lights section shows an album-cover-colours checkbox after the palette choice. Unticking it saves false, the next song fetches no cover, and the song playing at the time keeps its palette.
   - docs/how-it-works.md says the base colour is the most vivid colour covering about 5% or more of the album cover, that black, white and grey don't count, and that the app waits at most 1 second for the cover. It says a random palette is used for the whole song when the cover is missing, slow, unreadable or has no vivid colour, that covers are remembered by URL, and that light-show.album-cover-colours = false turns this off.
   - docs/roadmap.md no longer lists "Colours from the album cover". docs/architecture.md shows AlbumCovers between SyncEngine and the image CDN.
   - Tests: `CoverColourSpec` table of synthetic pixel vectors: mostly red, mostly blue, all neutral, neutral plus 4% vivid, neutral plus 6% vivid, and a 6% red patch split between 356° and 4°. A 6% patch split between the bin edge at 30° (hues 28° and 32°) gives a base within 10° of 30°. Two separate 10% patches at 0° and 120° give one of those hues, not a blend. Properties: every pixel neutral gives None. A Some result's hue window holds at least `MinShare` of the pixels. Rotating every pixel's hue by a multiple of 10° rotates the base by the same amount.

`PaletteSpec` properties: with a fixed seed, `random(t)(using new Random(s)) == RGB.random(using new Random(s)).flatMap(from(_, t))` for every `PaletteType`. A table gives each type the place it puts the base: Complementary, Triadic and Tetradic at index 0; Analogous at index 1; Monochromatic has every entry at the base's hue and saturation with brightnesses 0.35, 0.65 and 1.0; AnalogousComplementary(Flat) at index 1; Interspersed at index 2.

`AlbumCoversSpec` (CatsEffectSuite): `Client.fromHttpApp` serves PNGs built in memory, plus 404, 500, garbage bytes and a raised `ConnectException`. It asserts each `CoverError` case, and a request-counting `Ref` shows the cache hit and that failures aren't cached.

`PlaybackStateSpec` table: no album, empty images, images present.

`SyncEngineCoverSpec` under `TestControl`, with a seeded `Random` and fake Spotify over HTTP. Covers come from `AlbumCovers.fromHttp` on a `Client.fromHttpApp` that serves a red PNG, a blue PNG, a 500, garbage bytes, a raised connection error, and a PNG after `IO.sleep(2.seconds)`. The tests check `showFor(...).base` hue, WARN counts captured as `SyncEngineLogSpec` does (exactly one per song), the palette unchanged at 5 s, request counts across two tracks, the setting off (zero requests), `live.update` taking effect only at the next track, and `applyNow` with a new type giving `Palette.from(base, newType)`.

Config: `ConfigSource.string` without the key gives `albumCoverColours == true`, and a non-boolean value is rejected.

UI (Node.js): `SectionsSpec` 'every setting is on the page exactly once' covers the new key, and a SettingsPage test toggles the checkbox and checks the draft.

## Alternatives

- Fetch the cover in the poller as soon as a new URL appears, with `showFor` reading a `Deferred`. Rejected: it adds a fiber and shared state for a wait the issue already caps at 1 s.
- Plain fixed hue bins with no neighbours. Rejected: a colour lying across a bin edge (red at 355°–5°) splits below 5% and fails criterion 3.
- k-means or median-cut over the pixels. Rejected: harder to make deterministic and to test with tables than the windowed bins, which map directly onto the issue's rule.