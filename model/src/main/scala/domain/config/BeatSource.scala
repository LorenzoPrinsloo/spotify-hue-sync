package domain.config

import io.circe.{Codec, Decoder, Encoder}

/** Where the pulse timing comes from: Spotify's playback position, or the beat detected in live audio. */
enum BeatSource derives CanEqual:
  case Spotify, Audio

  /** How it's written in the config file: `spotify` or `audio`. */
  def name: String = toString.toLowerCase

object BeatSource:
  def parse(name: String): Option[BeatSource] = values.find(_.name == name.trim.toLowerCase)

  given Codec[BeatSource] =
    Codec.from(Decoder.decodeString.emap(n => parse(n).toRight(s"expected spotify or audio, not $n")), Encoder.encodeString.contramap(_.name))
