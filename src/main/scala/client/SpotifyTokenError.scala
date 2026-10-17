package client

import io.circe.parser.parse
import org.http4s.Status

/** Spotify's token endpoint refused a request. Messages carry Spotify's answer, never the token sent. */
sealed abstract class SpotifyTokenError(message: String) extends RuntimeException(message)

object SpotifyTokenError {

  /** Spotify's answer that the grant itself is no good: 400 with `error: invalid_grant`. */
  final case class Rejected(call: String, status: Status, body: String)
      extends SpotifyTokenError(s"Spotify rejected the $call, $status: $body")

  /** Any other unsuccessful answer, such as 429, `invalid_client` or Spotify being down: says nothing about the token. */
  final case class Failed(call: String, status: Status, body: String)
      extends SpotifyTokenError(s"Spotify $call failed, $status: $body")

  def of(call: String, status: Status, body: String): SpotifyTokenError = {
    val invalidGrant = parse(body).toOption
      .flatMap(_.hcursor.get[String]("error").toOption)
      .exists(_ == "invalid_grant")
    if (status == Status.BadRequest && invalidGrant) Rejected(call, status, body) else Failed(call, status, body)
  }
}
