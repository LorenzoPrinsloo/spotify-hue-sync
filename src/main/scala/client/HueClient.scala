package client

import cats.effect.*
import org.http4s.client.Client
import org.http4s.circe.CirceEntityCodec.*
import io.circe.generic.auto.*
import domain.hue.HueCredentials
import org.typelevel.log4cats.slf4j.Slf4jLogger
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import org.typelevel.ci.CIStringSyntax
import org.http4s.*

class HueClient[F[_]: Async](
  creds: HueCredentials,
  httpClient: Client[F]
  ) {
  private val logger = Slf4jLogger.getLogger[F]
  private val baseUri = Uri.unsafeFromString(s"https://${creds.address}/clip/v2")

  private val channelId: Decoder[Int] = Decoder.instance(_.downField("channel_id").as[Int])

  /** The channel IDs of an entertainment zone, sorted, as the bridge reports them. */
  def getChannels(zoneId: String): F[List[Int]] = {
    val uri = baseUri / "resource" / "entertainment_configuration" / zoneId
    val request = Request[F](Method.GET, uri).withHeaders(Header.Raw(ci"hue-application-key", creds.username))

    httpClient.run(request).use { response =>
      if (!response.status.isSuccess)
        EntityDecoder.decodeText(response).flatMap { body =>
          Async[F].raiseError(HueApiError(s"Couldn't read entertainment zone $zoneId: ${response.status} $body"))
        }
      else
        response.as[Json].flatMap { json =>
          json.hcursor.downField("data").downN(0).downField("channels").as[List[Int]](Decoder.decodeList(channelId)) match {
            case Right(ids) if ids.nonEmpty => Async[F].pure(ids.distinct.sorted)
            case Right(_)                   => Async[F].raiseError(HueApiError(s"Entertainment zone $zoneId has no channels"))
            case Left(err)                  => Async[F].raiseError(HueApiError(s"Unexpected entertainment zone response: ${err.getMessage}"))
          }
        }
    }
  }

  /**
   * Tells the bridge to start (or stop) the entertainment session.
   * This opens port 2100 for DTLS traffic.
   */
  def setStreaming(zoneId: String, active: Boolean): F[Unit] = {
    val uri = baseUri / "resource"  / "entertainment_configuration" / zoneId
    val body = Json.obj("action" -> Json.fromString(if(active) "start" else "stop"))
    val request = Request[F](Method.PUT, uri).withEntity(body).withHeaders(
      Header.Raw(ci"hue-application-key", creds.username)
    )

    for {
      _ <- logger.info(s"Setting Hue streaming to $active for zone $zoneId")
      _ <- httpClient.run(request).use { response =>
        if (response.status.isSuccess) Async[F].unit
        else {
          EntityDecoder.decodeText(response)
            .flatMap { json =>
              logger.error(s"Spotify Exchange Failed. Body: $body") *> Async[F].raiseError(new Exception(s"Hue API error: ${response.status} $json"))
            }
        }
      }
    } yield ()
  }
}

case class HueApiError(message: String) extends Exception(message)
