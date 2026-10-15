package domain.config

/**
 * Live beat detection. `device` is matched against input names (e.g. "BlackHole"); `offsetMs` shifts the lights
 * relative to the detected beat, positive being earlier, to make up for capture and bridge latency. Through quiet
 * or unclear passages the last confident beat is kept for `holdSeconds`.
 */
case class AudioSettings(
  device: String = "BlackHole",
  offsetMs: Long = 100,
  minBpm: Double = 60,
  maxBpm: Double = 180,
  holdSeconds: Double = 10.0
)
