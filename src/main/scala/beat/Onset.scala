package beat

/**
 * What one hop of audio sounded like.
 *
 * `flux` is the onset strength the beat tracker uses (low end weighted most), `bassFlux` the same for the low end
 * alone (kick and bass), `trebleFlux` for the high end alone (hi-hats, cymbals, consonants), and `rms` the hop's
 * loudness. `kick` is how much the power in the kick drum's range (about 40-130 Hz) rose since the previous window:
 * unlike the flux, it isn't compressed, so the big low thump of a kick stands out from the bass line around it.
 * `pitch` is where the sound in the middle of the spectrum (vocals, leads, chords) is centred, in octaves above 1 Hz,
 * or None when that range is silent.
 */
case class Onset(flux: Double, bassFlux: Double, trebleFlux: Double, rms: Double, kick: Double, pitch: Option[Double])
