package givens.circe

import io.circe.{Codec, Decoder, Encoder}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

object common {
  given Codec[FiniteDuration] = Codec.from(
    Decoder[Int].map(_.seconds),
    Encoder[Int].contramap(_.toSeconds.toInt)
  )
}
