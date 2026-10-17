package client

import org.http4s.Status

import scala.concurrent.duration.FiniteDuration

/** Why an album cover's colour couldn't be read. Each one leaves the song with a random palette. */
enum CoverError(val message: String) extends RuntimeException(message) derives CanEqual {
  case Refused(url: String, status: Status) extends CoverError(s"The album cover at $url answered $status")
  case Unreachable(url: String, error: Throwable) extends CoverError(s"Couldn't reach the album cover at $url: $error")
  case Unreadable(url: String) extends CoverError(s"The album cover at $url isn't an image that can be read")
  case TimedOut(url: String, after: FiniteDuration) extends CoverError(s"The album cover at $url didn't arrive within $after")

  /** The case's name in snake_case, for the log's `reason` field. */
  def reason: String = productPrefix.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase
}
