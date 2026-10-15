package beat

/**
 * The kick pattern of a bar: how hard the kick drum's range hits on each sixteenth of a four-beat bar. It's learnt
 * along the beat grid, so the lights can play it back on time, ahead of each hit, instead of reacting to hits after
 * they're heard.
 *
 * `shape` averages each sixteenth over the last several bars, which is steady enough to show the pattern; `recent`
 * over the last bar or two, which shows whether it's still being played. `usualLift` is how far the pattern usually
 * stands out, `learnt` how many sixteenths have been heard, and `step` the one being heard now, counted along the grid,
 * with the `sum` and `count` of the kick power heard in it so far.
 */
case class Groove(
  shape: Vector[Double],
  recent: Vector[Double],
  usualLift: Double,
  learnt: Long,
  step: Long,
  sum: Double = 0.0,
  count: Int = 0
) {

  /** Moves on to `beats` beats into the grid, having just heard `kick` power in the kick drum's range. */
  def at(beats: Double, kick: Double): Groove = {
    val now = Groove.stepAt(beats)
    if (now <= step) copy(sum = sum + kick, count = count + 1)
    else {
      // The step just finished learns what was heard in it; any skipped over (the grid can jump) learn nothing.
      val heard = Option.unless(count == 0)(sum / count)
      val learning = heard.fold(this) { power =>
        val slot = Groove.slot(step)
        val bars = learnt / Groove.Steps
        def learn(values: Vector[Double], rate: Double) =
          values.updated(slot, values(slot) + math.max(rate, 1.0 / (bars + 1)) * (power - values(slot)))
        val nextRecent = learn(recent, Groove.RecentRate)
        val usual = usualLift + math.max(Groove.UsualRate, 1.0 / (learnt + 1)) * (Groove.lift(nextRecent) - usualLift)
        copy(shape = learn(shape, Groove.ShapeRate), recent = nextRecent, usualLift = usual, learnt = learnt + 1)
      }
      learning.copy(step = now, sum = kick, count = 1)
    }
  }

  /**
   * How strongly each sixteenth of the bar is played, from 0 to 1: the loudest is 1 and anything no louder than the
   * typical sixteenth is 0. While the kicks are quieter than usual, or gone, the whole pattern fades with them. Until
   * a full bar has been heard, a plain kick on every beat.
   */
  def pattern: Vector[Double] =
    if (learnt < Groove.Steps) Groove.Prior
    else {
      val sorted = shape.sorted
      val middle = sorted(Groove.Steps / 2)
      val lift = sorted.last - middle
      val playing = if (usualLift <= 1e-12) 0.0 else math.min(1.0, Groove.lift(recent) / usualLift)
      if (lift <= 1e-12) Vector.fill(Groove.Steps)(0.0)
      else shape.map(v => math.max(0.0, math.min(1.0, (v - middle) / lift)) * playing)
    }
}

object Groove {
  val BeatsPerBar: Int = 4
  val StepsPerBeat: Int = 4
  val Steps: Int = BeatsPerBar * StepsPerBeat

  /** How much one bar changes the pattern's shape: it settles over six or seven bars. */
  val ShapeRate: Double = 0.15

  /** How much one bar changes the recent pattern: a bar or two without kicks shows. */
  val RecentRate: Double = 0.5

  /** How much one sixteenth changes how far the pattern usually stands out: about ten bars. */
  val UsualRate: Double = 0.006

  /** A plain kick on every beat. */
  val Prior: Vector[Double] = Vector.tabulate(Steps)(i => if (i % StepsPerBeat == 0) 1.0 else 0.0)

  /** A fresh groove at `beats` beats into the grid. */
  def start(beats: Double): Groove =
    Groove(Vector.fill(Steps)(0.0), Vector.fill(Steps)(0.0), 0.0, 0L, stepAt(beats))

  /** The sixteenth nearest to `beats` beats into the grid, so a kick slightly ahead of the grid counts as on it. */
  def stepAt(beats: Double): Long = math.round(beats * StepsPerBeat)

  def slot(step: Long): Int = Math.floorMod(step, Steps.toLong).toInt

  /** How far the loudest sixteenth stands above the typical one. */
  def lift(values: Vector[Double]): Double = {
    val sorted = values.sorted
    sorted.last - sorted(Steps / 2)
  }
}
