package beat

object Fft {

  /** Magnitude spectrum of a real signal whose length is a power of two; returns the first n / 2 bins. */
  def magnitudes(signal: Array[Double]): Array[Double] = {
    val n = signal.length
    require(n > 0 && (n & (n - 1)) == 0, s"FFT size must be a power of two, got $n")
    val re = signal.clone()
    val im = new Array[Double](n)

    var j = 0
    for (i <- 1 until n) {
      var bit = n >> 1
      while ((j & bit) != 0) { j ^= bit; bit >>= 1 }
      j |= bit
      if (i < j) {
        val t = re(i); re(i) = re(j); re(j) = t
      }
    }

    var len = 2
    while (len <= n) {
      val angle = -2 * math.Pi / len
      val (wr, wi) = (math.cos(angle), math.sin(angle))
      var start = 0
      while (start < n) {
        var (cr, ci) = (1.0, 0.0)
        for (k <- 0 until len / 2) {
          val a = start + k
          val b = a + len / 2
          val tr = re(b) * cr - im(b) * ci
          val ti = re(b) * ci + im(b) * cr
          re(b) = re(a) - tr; im(b) = im(a) - ti
          re(a) += tr; im(a) += ti
          val next = cr * wr - ci * wi
          ci = cr * wi + ci * wr
          cr = next
        }
        start += len
      }
      len <<= 1
    }

    Array.tabulate(n / 2)(k => math.hypot(re(k), im(k)))
  }
}
