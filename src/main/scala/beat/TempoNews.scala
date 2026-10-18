package beat

/** Something worth telling the listener about how the tempo was chosen: the tempo in BPM that was left, and the one moved to. */
enum TempoNews derives CanEqual {

  /** The kick preferred a related tempo `to` over the all-onset winner `from`, and that became the reported tempo. */
  case KickMoved(from: Double, to: Double)

  /** The tracker followed a related tempo `to` after it held, leaving `from`. */
  case FollowedRelated(from: Double, to: Double)
}
