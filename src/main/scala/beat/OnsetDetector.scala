package beat

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
    val (flux, bassFlux, trebleFlux) =
      if (previous.isEmpty) (0.0, 0.0, 0.0)
      else {
        val rise = spectrum.indices.map(k => math.max(0.0, spectrum(k) - previous(k)))
        val (low, high) = rise.splitAt(OnsetDetector.lowBins)
        val treble = rise.drop(OnsetDetector.trebleBins)
        val bass = low.sum / low.size
        (bass + OnsetDetector.highWeight * high.sum / high.size, bass, treble.sum / treble.size)
      }
    val rms = if (hop.isEmpty) 0.0 else math.sqrt(hop.map(s => s.toDouble * s).sum / hop.length)
    val kick = if (previous.isEmpty) 0.0 else math.max(0.0, OnsetDetector.kickPower(spectrum) - OnsetDetector.kickPower(previous))
    val onset = Onset(flux, bassFlux, trebleFlux, rms, kick, OnsetDetector.pitch(magnitudes, window))
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

  /** Bins of about 40-130 Hz (at 44.1 kHz with a 1024 window), where a kick drum thumps. */
  val kickBins: Range = 1 to 3

  /** Power in the kick drum's range, from the compressed spectrum. */
  private def kickPower(spectrum: scala.collection.IndexedSeq[Double]): Double =
    kickBins.filter(_ < spectrum.size).map { k =>
      val magnitude = (math.exp(spectrum(k)) - 1) / 100
      magnitude * magnitude
    }.sum

  /** Each bin's frequency in octaves above 1 Hz, per window size. */
  private val octavesOf = scala.collection.concurrent.TrieMap.empty[Int, Array[Double]]

  /**
   * The loudness-weighted centre of the spectrum between the low end and the treble, in octaves above 1 Hz, so a
   * move of 1 is an octave. None when there's next to nothing there.
   */
  def pitch(magnitudes: Array[Double], window: Int, sampleRate: Double = 44100): Option[Double] = {
    val octaves = octavesOf.getOrElseUpdate(window, Array.tabulate(window / 2 + 1)(k => math.log(math.max(k, 1) * sampleRate / window) / math.log(2)))
    val end = math.min(trebleBins, magnitudes.length)
    var weight = 0.0
    var sum = 0.0
    var k = lowBins
    while (k < end) {
      weight += magnitudes(k)
      sum += magnitudes(k) * octaves(k)
      k += 1
    }
    Option.when(weight > 1e-6)(sum / weight)
  }

  private val windows = scala.collection.concurrent.TrieMap.empty[Int, Array[Double]]

  def hann(n: Int): Array[Double] =
    windows.getOrElseUpdate(n, Array.tabulate(n)(i => 0.5 - 0.5 * math.cos(2 * math.Pi * i / (n - 1))))
}
