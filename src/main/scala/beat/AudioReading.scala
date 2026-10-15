package beat

/**
 * What the music is doing right now.
 *
 * `energy` and `treble` compare the last fraction of a second with the track's usual level, so 1.0 means typical,
 * below 1 quieter or duller, above 1 louder or brighter. `hit` is the latest big hit (a crash or a drop), `sparkle`
 * the latest treble onset (hi-hats, cymbals), and `sparkles` counts them, so each can land on a different light.
 * `pitch` is how high the vocals, leads and chords sit compared with usual for the track, from -1 (well below) to 1
 * (well above). `groove` is the bar's kick pattern (see [[Groove]]), empty without a beat.
 */
case class AudioReading(
  beat: Option[BeatState],
  energy: Double = 1.0,
  treble: Double = 1.0,
  hit: Option[Hit] = None,
  sparkle: Option[Hit] = None,
  sparkles: Long = 0,
  pitch: Double = 0.0,
  groove: Vector[Double] = Vector.empty
)
