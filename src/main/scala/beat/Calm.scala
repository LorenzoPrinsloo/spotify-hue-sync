package beat

import domain.config.RhythmSettings

/** Calms the kick pattern for the rhythm lights: weak sixteenths dropped, close kicks merged, strength steadied. */
object Calm {

  /** Below this the kicks have dropped out or are only coming back, and the strength follows them at once. */
  val DropOut: Double = 0.4

  /** While smoothing, a strength below this shows as rest. */
  val Resting: Double = 0.2

  /** How far a smoothed value may move in a bar at smoothing 0.5; it scales with (1 - s) / s. */
  val StepPerBar: Double = 0.03

  /** The least a smoothed value moves in a bar, however high the smoothing, so it always catches up. */
  val MinStepPerBar: Double = 0.02

  /** The furthest a smoothed value stays from what it follows; bar-to-bar jitter is well inside this. */
  val Jitter: Double = 0.35

  private val Epsilon = 1e-9

  /**
   * What the rhythm lights are doing: the kicks' smoothed `strength` and the music's smoothed `energy` (None while
   * not smoothing), the sixteenths `learnt` when they last moved, the grid step of the last pulse played, and the
   * `pattern` being played.
   */
  case class State(
    strength: Double = 0.0,
    energy: Option[Double] = None,
    learnt: Long = 0L,
    lastPulse: Option[Long] = None,
    pattern: Vector[Double] = Vector.empty
  )

  /** `levels` thresholded, scaled by the strength shown and spaced at least `minGapMs` apart at `bpm`. */
  def pattern(levels: Vector[Double], strength: Double, settings: RhythmSettings, bpm: Double): Vector[Double] =
    spaced(levels.map(level => threshold(level, settings.threshold) * strength), gapSteps(settings.minGapMs, bpm))
      .map(v => math.max(0.0, math.min(1.0, v)))

  /** 0 up to `t`, then rising to 1 at the loudest: (v - t) / (1 - t). */
  def threshold(level: Double, t: Double): Double =
    if (level <= t) 0.0 else (level - t) / (1.0 - t)

  /** The fewest sixteenths at `bpm` that are at least `minGapMs` long; at least 1. */
  def gapSteps(minGapMs: Long, bpm: Double): Int =
    if (bpm <= 0 || minGapMs <= 0) 1
    else math.max(1, math.ceil(minGapMs * bpm / 15000.0 - Epsilon).toInt)

  /**
   * Lit sixteenths fewer than `gap` apart, around the bar, merged into one on the group's first, as strong as its
   * strongest. Grouping starts after the longest unlit run; a last group too close to the bar's first joins the one before.
   */
  def spaced(pattern: Vector[Double], gap: Int): Vector[Double] = {
    val size = pattern.size
    val lit = pattern.indices.filter(pattern(_) > 0).toVector
    if (gap <= 1 || lit.size < 2) pattern
    else render(joinLast(groups(pattern, lit, groupStart(lit, size), gap), size, gap), size)
  }

  private case class Group(slot: Int, offset: Int, max: Double)

  /** The index in `lit` of the first slot after the longest unlit run. */
  private def groupStart(lit: Vector[Int], size: Int): Int = {
    val unlitBefore = lit.indices.map(j => Math.floorMod(lit(j) - lit(Math.floorMod(j - 1, lit.size)) - 1, size))
    unlitBefore.indexOf(unlitBefore.max)
  }

  /** The lit slots from `start` around the bar, grouped while fewer than `gap` from the group's first; latest first. */
  private def groups(pattern: Vector[Double], lit: Vector[Int], start: Int, gap: Int): List[Group] = {
    val ordered = (lit.drop(start) ++ lit.take(start)).map(slot => (slot, Math.floorMod(slot - lit(start), pattern.size)))
    ordered.foldLeft(List.empty[Group]) {
      case (current :: earlier, (slot, offset)) if offset - current.offset < gap =>
        current.copy(max = math.max(current.max, pattern(slot))) :: earlier
      case (found, (slot, offset)) => Group(slot, offset, pattern(slot)) :: found
    }
  }

  private def render(groups: List[Group], size: Int): Vector[Double] =
    groups.foldLeft(Vector.fill(size)(0.0))((out, g) => out.updated(g.slot, g.max))

  private def joinLast(groups: List[Group], size: Int, gap: Int): List[Group] = groups match {
    case last :: previous :: earlier if size - last.offset < gap =>
      joinLast(previous.copy(max = math.max(previous.max, last.max)) :: earlier, size, gap)
    case other => other
  }

  /** `pattern` with every slot whose next grid step after `step` is fewer than `gap` after `lastPulse` dark. */
  def held(pattern: Vector[Double], step: Long, lastPulse: Option[Long], gap: Int): Vector[Double] =
    lastPulse.fold(pattern) { last =>
      val upcoming = Groove.slot(step + 1)
      pattern.zipWithIndex.map { (v, slot) =>
        val next = step + 1 + Math.floorMod(slot - upcoming, Groove.Steps)
        if (next - last >= 1 && next - last <= gap - 1) 0.0 else v
      }
    }

  /**
   * `current` after `sixteenths` more sixteenths heard, moving towards `target` by at most
   * max([[MinStepPerBar]], [[StepPerBar]] (1 - s) / s) a bar, and never more than [[Jitter]] from it; `target` at s = 0.
   */
  def follow(current: Double, target: Double, smoothing: Double, sixteenths: Long): Double =
    if (smoothing <= 0) target
    else {
      val perBar = math.max(MinStepPerBar, StepPerBar * (1.0 - smoothing) / smoothing)
      val room = perBar * sixteenths / Groove.Steps
      val distance = target - current
      val moved = current + math.signum(distance) * math.min(math.abs(distance), room)
      math.max(target - Jitter, math.min(target + Jitter, moved))
    }

  /** Like [[follow]], but taking `playing` at once while it or `current` is below [[DropOut]]. */
  def strength(current: Double, playing: Double, smoothing: Double, sixteenths: Long): Double =
    if (smoothing <= 0 || playing < DropOut || current < DropOut) playing
    else follow(current, playing, smoothing, sixteenths)

  /** The strength the lights show: rest below [[Resting]] while smoothing. */
  def shown(strength: Double, smoothing: Double): Double =
    if (smoothing > 0 && strength < Resting) 0.0 else strength
}
