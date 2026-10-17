package client

import io.circe.parser.parse
import org.http4s.Status

/** Spotify's token endpoint refused a request. Messages carry Spotify's answer, never the token sent. */
enum SpotifyTokenError extends RuntimeException derives CanEqual {

  /** Spotify's answer that the grant itself is no good: 400 with `error: invalid_grant`. */
  case Rejected(call: String, status: Status, body: String)

  /** Any other unsuccessful answer, such as 429, `invalid_client` or Spotify being down: says nothing about the token. */
  case Failed(call: String, status: Status, body: String)

  override def getMessage: String = this match {
    case Rejected(call, status, body) => s"Spotify rejected the $call, $status: $body"
    case Failed(call, status, body) => s"Spotify $call failed, $status: $body"
  }
}

object SpotifyTokenError {

  def of(call: String, status: Status, body: String): SpotifyTokenError = {
    val invalidGrant = parse(body).toOption
      .flatMap(_.hcursor.get[String]("error").toOption)
      .exists(_ == "invalid_grant")
    if (status == Status.BadRequest && invalidGrant) Rejected(call, status, body) else Failed(call, status, body)
  }
}
