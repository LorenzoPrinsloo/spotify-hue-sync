package client

import cats.effect.*
import org.http4s.client.Client
import org.http4s.circe.CirceEntityCodec.*
import io.circe.generic.auto.*
import domain.hue.{HueCredentials, MusicArea}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import cats.syntax.all.*
import io.circe.{ACursor, Decoder, Json}
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

  /** A list of the bridge's resources of one kind, such as `room`. */
  private def resources(kind: String): F[List[Json]] = {
    val request = Request[F](Method.GET, baseUri / "resource" / kind).withHeaders(Header.Raw(ci"hue-application-key", creds.username))
    httpClient.run(request).use { response =>
      if (!response.status.isSuccess)
        EntityDecoder.decodeText(response).flatMap(body => Async[F].raiseError(HueApiError(s"Couldn't read the bridge's $kind list: ${response.status} $body")))
      else
        response.as[Json].flatMap { json =>
          json.hcursor.downField("data").as[List[Json]].leftMap(err => HueApiError(s"Unexpected $kind list from the bridge: ${err.getMessage}")).liftTo[F]
        }
    }
  }

  /**
   * The bridge's entertainment areas (the Hue app's music and TV areas), music areas first, each with the rooms its
   * lights are in and how many lights it has.
   */
  def areas: F[List[MusicArea]] =
    for {
      configurations <- resources("entertainment_configuration")
      services <- resources("entertainment")
      rooms <- resources("room")
    } yield {
      def text(json: Json, path: String*): Option[String] = path.foldLeft[ACursor](json.hcursor)(_.downField(_)).as[String].toOption
      val deviceOf = services.flatMap(s => (text(s, "id"), text(s, "owner", "rid")).tupled).toMap
      val roomOf = rooms.flatMap { room =>
        val name = text(room, "metadata", "name")
        room.hcursor.downField("children").as[List[Json]].getOrElse(Nil).flatMap(child => (text(child, "rid"), name).tupled)
      }.toMap
      configurations.flatMap { config =>
        val devices = config.hcursor.downField("channels").as[List[Json]].getOrElse(Nil)
          .flatMap(_.hcursor.downField("members").as[List[Json]].getOrElse(Nil))
          .flatMap(member => text(member, "service", "rid").flatMap(deviceOf.get))
          .distinct
        (text(config, "id"), text(config, "metadata", "name")).mapN { (id, name) =>
          MusicArea(id, name, text(config, "configuration_type").getOrElse("other"), devices.flatMap(roomOf.get).distinct, devices.size)
        }
      }.sortBy(area => (!area.isMusic, area.name.toLowerCase))
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
        else
          EntityDecoder.decodeText(response).flatMap { text =>
            Async[F].raiseError(HueApiError(s"Couldn't ${if (active) "start" else "stop"} streaming on $zoneId: ${response.status} $text"))
          }
      }
    } yield ()
  }
}

case class HueApiError(message: String) extends Exception(message)
