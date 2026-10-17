package client

import org.http4s.Status

import scala.concurrent.duration.FiniteDuration

/** Why an album cover's colour couldn't be read. Each one leaves the song with a random palette. */
sealed abstract class CoverError(message: String) extends RuntimeException(message) {

  /** The case's name in the log's `reason` field. */
  def reason: String = this match {
    case _: CoverError.Refused => "refused"
    case _: CoverError.Unreachable => "unreachable"
    case _: CoverError.Unreadable => "unreadable"
    case _: CoverError.TimedOut => "timed_out"
  }
}

object CoverError {
  final case class Refused(url: String, status: Status) extends CoverError(s"The album cover at $url answered $status")
  final case class Unreachable(url: String, error: Throwable) extends CoverError(s"Couldn't reach the album cover at $url: $error")
  final case class Unreadable(url: String) extends CoverError(s"The album cover at $url isn't an image that can be read")
  final case class TimedOut(url: String, after: FiniteDuration) extends CoverError(s"The album cover at $url didn't arrive within $after")
}
