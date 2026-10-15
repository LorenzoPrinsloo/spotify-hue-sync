package domain.config

/** Where the pulse timing comes from: Spotify's playback position, or the beat detected in live audio. */
enum BeatSource derives CanEqual:
  case Spotify, Audio
