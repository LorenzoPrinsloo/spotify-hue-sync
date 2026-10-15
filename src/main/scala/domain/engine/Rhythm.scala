package domain.engine

/** The bar's kick pattern (see [[beat.Groove]]), and how many beats into the beat grid the show is. */
case class Rhythm(groove: Vector[Double], beats: Double)
