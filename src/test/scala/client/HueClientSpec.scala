package client

import cats.effect.{IO, Ref}
import domain.hue.HueCredentials
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.typelevel.ci.CIStringSyntax

class HueClientSpec extends CatsEffectSuite {

  private val creds = HueCredentials("10.0.0.2", "app-key", "00ff")

  private def zoneJson(channels: String) =
    s"""{ "errors": [], "data": [ { "id": "zone-1", "type": "entertainment_configuration", "channels": [ $channels ] } ] }"""

  private def bridge(status: Status, body: String, seenKey: Ref[IO, Option[String]]): Client[IO] =
    Client.fromHttpApp(HttpRoutes.of[IO] {
      case req @ GET -> Root / "clip" / "v2" / "resource" / "entertainment_configuration" / "zone-1" =>
        seenKey.set(req.headers.get(ci"hue-application-key").map(_.head.value)) *>
          IO.pure(Response[IO](status).withEntity(body))
    }.orNotFound)

  test("reads the zone's channel ids from the bridge, sorted") {
    for {
      key <- Ref.of[IO, Option[String]](None)
      json = zoneJson("""{ "channel_id": 2, "members": [] }, { "channel_id": 0, "members": [] }, { "channel_id": 1, "members": [] }""")
      channels <- new HueClient[IO](creds, bridge(Status.Ok, json, key)).getChannels("zone-1")
      sentKey <- key.get
    } yield {
      assertEquals(channels, List(0, 1, 2))
      assertEquals(sentKey, Some("app-key"))
    }
  }

  test("a zone with no channels is an error") {
    for {
      key <- Ref.of[IO, Option[String]](None)
      result <- new HueClient[IO](creds, bridge(Status.Ok, zoneJson(""), key)).getChannels("zone-1").attempt
    } yield assert(result match { case Left(_: HueApiError) => true; case _ => false }, result.toString)
  }

  test("a bridge error is reported with the status") {
    for {
      key <- Ref.of[IO, Option[String]](None)
      result <- new HueClient[IO](creds, bridge(Status.Forbidden, "nope", key)).getChannels("zone-1").attempt
    } yield assert(result.left.exists(e => e.getMessage.contains("403")), result.toString)
  }

  test("an unexpected response shape is an error") {
    for {
      key <- Ref.of[IO, Option[String]](None)
      result <- new HueClient[IO](creds, bridge(Status.Ok, """{ "data": [] }""", key)).getChannels("zone-1").attempt
    } yield assert(result match { case Left(_: HueApiError) => true; case _ => false }, result.toString)
  }
}
