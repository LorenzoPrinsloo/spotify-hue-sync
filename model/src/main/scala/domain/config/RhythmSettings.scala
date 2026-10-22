package domain.config

import io.circe.Codec

/**
 * How calmly the rhythm lights follow the kick pattern. Kicks closer together than `minGapMs` merge into one pulse on
 * the first kick's sixteenth, as strong as the strongest. A sixteenth lights only once it stands out by `threshold` of
 * the gap between the bar's typical and loudest sixteenth. `smoothing` slows how far the rhythm lights' strength can
 * move from one bar to the next. 0 switches each one off.
 */
case class RhythmSettings(
  minGapMs: Long = 160,
  threshold: Double = 0.3,
  smoothing: Double = 0.5
) derives Codec.AsObject
