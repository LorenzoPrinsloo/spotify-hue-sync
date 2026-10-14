package beat

/**
 * Spectral-flux onset detection: how much louder each frequency got since the previous window. Drums and note
 * attacks make it spike; steady sounds keep it near zero. The low end counts most, because the kick and bass
 * usually carry the beat while hi-hats fill in between.
 */
case class OnsetDetector(window: Int, history: Vector[Float], previous: Vector[Double]) {

  /** Feeds one hop of new samples; returns the next state, the onset strength and the hop's RMS loudness. */
  def step(hop: Array[Float]): (OnsetDetector, Double, Double) = {
    val samples = (history ++ hop).takeRight(window)
    val padded = samples.padTo(window, 0f)
    val windowed = Array.tabulate(window)(i => padded(i) * OnsetDetector.hann(window)(i))
    val spectrum = Fft.magnitudes(windowed).map(m => math.log1p(100 * m))
    val flux =
      if (previous.isEmpty) 0.0
      else {
        val rise = spectrum.indices.map(k => math.max(0.0, spectrum(k) - previous(k)))
        val (low, high) = rise.splitAt(OnsetDetector.lowBins)
        low.sum / low.size + OnsetDetector.highWeight * high.sum / high.size
      }
    val rms = if (hop.isEmpty) 0.0 else math.sqrt(hop.map(s => s.toDouble * s).sum / hop.length)
    (OnsetDetector(window, samples, spectrum.toVector), flux, rms)
  }
}

object OnsetDetector {
  def initial(window: Int): OnsetDetector = OnsetDetector(window, Vector.empty, Vector.empty)

  /** Bins below about 300 Hz (at 44.1 kHz with a 1024 window), where kicks and bass carry the beat. */
  val lowBins: Int = 7

  /** How much the rest of the spectrum (hi-hats, vocals) counts compared with the low end. */
  val highWeight: Double = 0.3

  private val windows = scala.collection.concurrent.TrieMap.empty[Int, Array[Double]]

  def hann(n: Int): Array[Double] =
    windows.getOrElseUpdate(n, Array.tabulate(n)(i => 0.5 - 0.5 * math.cos(2 * math.Pi * i / (n - 1))))
}
