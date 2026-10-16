package beat

/**
 * Running averages of loudness, of treble activity (how much is happening in the high end: hi-hats, cymbals, the
 * bite of vocals) and of pitch: a short one for "now" and a long one for "usual". `pitchSpread` is how far pitch
 * usually strays from the usual.
 */
case class Levels(
  loudFast: Double = 0.0,
  loudNow: Double = 0.0,
  loudUsual: Double = 0.0,
  trebleNow: Double = 0.0,
  trebleUsual: Double = 0.0,
  heard: Long = 0,
  pitchNow: Double = 0.0,
  pitchUsual: Double = 0.0,
  pitchSpread: Double = 0.0
) {

  /** `trebleExcess` is how far a treble-led onset rose above the usual background, or 0. */
  def update(onset: Onset, trebleExcess: Double, s: TrackerSettings): Levels = {
    // Over a second or two: energy describes the section of the song, not each kick.
    val now = s.smoothing(1.5)
    // The usual level is learnt only from music, not silence, and starts as a plain average so the first
    // seconds of a track aren't compared with zero.
    val playing = onset.rms >= s.silenceRms
    val counted = if (playing) heard + 1 else heard
    val usual = if (playing) math.max(1.0 / counted, s.smoothing(20.0)) else 0.0
    val pitch = onset.pitch.filter(_ => playing)
    val nextPitchNow = pitch.fold(pitchNow)(p => pitchNow + (p - pitchNow) * math.max(1.0 / counted, s.smoothing(Levels.PitchSeconds)))
    val nextPitchUsual = pitch.fold(pitchUsual)(p => pitchUsual + (p - pitchUsual) * usual)
    Levels(
      loudFast = loudFast + (onset.rms - loudFast) * s.smoothing(0.05),
      loudNow = loudNow + (onset.rms - loudNow) * now,
      loudUsual = loudUsual + (onset.rms - loudUsual) * usual,
      trebleNow = trebleNow + (trebleExcess - trebleNow) * s.smoothing(0.6),
      trebleUsual = trebleUsual + (trebleExcess - trebleUsual) * usual,
      heard = counted,
      pitchNow = nextPitchNow,
      pitchUsual = nextPitchUsual,
      pitchSpread = pitch.fold(pitchSpread)(_ => pitchSpread + (math.abs(nextPitchNow - nextPitchUsual) - pitchSpread) * usual)
    )
  }

  def energy: Double = if (loudUsual <= 1e-9) 1.0 else loudNow / loudUsual

  // The small constant keeps the ratio sensible when a track has had almost no treble so far.
  def treble: Double = (trebleNow + 0.01) / (trebleUsual + 0.01)

  /** How far pitch is from usual, scaled so a typical move is about half way to the -1 or 1 it's capped at. */
  def pitch: Double = {
    val scale = math.max(Levels.PitchRange * pitchSpread, Levels.MinPitchRange)
    math.max(-1.0, math.min(1.0, (pitchNow - pitchUsual) / scale))
  }
}

object Levels {

  /** Pitch follows the music over about this long: long enough to ride over single drum hits, short enough for a melody. */
  val PitchSeconds: Double = 0.4

  /** Pitch this many typical moves away from usual counts as fully high or low. */
  val PitchRange: Double = 2.5

  /** Octaves that count as fully high or low at the least, so a track that hardly moves doesn't swing the colours. */
  val MinPitchRange: Double = 0.1
}
