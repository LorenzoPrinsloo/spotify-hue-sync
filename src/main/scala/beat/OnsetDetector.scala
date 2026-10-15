package beat

/**
 * What one hop of audio sounded like.
 *
 * `flux` is the onset strength the beat tracker uses (low end weighted most), `trebleFlux` the same for the high
 * end alone (hi-hats, cymbals, consonants), and `rms` the hop's loudness.
 */
case class Onset(flux: Double, trebleFlux: Double, rms: Double)

/**
 * Spectral-flux onset detection: how much louder each frequency got since the previous window. Drums and note
 * attacks make it spike; steady sounds keep it near zero. The low end counts most, because the kick and bass
 * usually carry the beat while hi-hats fill in between.
 */
case class OnsetDetector(window: Int, history: Vector[Float], previous: Vector[Double]) {

  /** Feeds one hop of new samples; returns the next state and what the hop sounded like. */
  def step(hop: Array[Float]): (OnsetDetector, Onset) = {
    val samples = (history ++ hop).takeRight(window)
    val padded = samples.padTo(window, 0f)
    val windowed = Array.tabulate(window)(i => padded(i) * OnsetDetector.hann(window)(i))
    val magnitudes = Fft.magnitudes(windowed)
    val spectrum = magnitudes.map(m => math.log1p(100 * m))
    val (flux, trebleFlux) =
      if (previous.isEmpty) (0.0, 0.0)
      else {
        val rise = spectrum.indices.map(k => math.max(0.0, spectrum(k) - previous(k)))
        val (low, high) = rise.splitAt(OnsetDetector.lowBins)
        val treble = rise.drop(OnsetDetector.trebleBins)
        (low.sum / low.size + OnsetDetector.highWeight * high.sum / high.size, treble.sum / treble.size)
      }
    val rms = if (hop.isEmpty) 0.0 else math.sqrt(hop.map(s => s.toDouble * s).sum / hop.length)
    val onset = Onset(flux, trebleFlux, rms)
    (OnsetDetector(window, samples, spectrum.toVector), onset)
  }
}

object OnsetDetector {
  def initial(window: Int): OnsetDetector = OnsetDetector(window, Vector.empty, Vector.empty)

  /** Bins below about 300 Hz (at 44.1 kHz with a 1024 window), where kicks and bass carry the beat. */
  val lowBins: Int = 7

  /** Bins above about 4 kHz, where hi-hats, cymbals and the bite of vocals sit. */
  val trebleBins: Int = 93

  /** How much the rest of the spectrum (hi-hats, vocals) counts compared with the low end. */
  val highWeight: Double = 0.3

  private val windows = scala.collection.concurrent.TrieMap.empty[Int, Array[Double]]

  def hann(n: Int): Array[Double] =
    windows.getOrElseUpdate(n, Array.tabulate(n)(i => 0.5 - 0.5 * math.cos(2 * math.Pi * i / (n - 1))))
}
