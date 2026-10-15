package client

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import domain.hue.{HueCredentials, MusicArea}
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

  test("lists the entertainment areas, music first, with the rooms their lights are in") {
    val configurations =
      """{ "data": [
        |  { "id": "tv", "metadata": { "name": "TV" }, "configuration_type": "screen",
        |    "channels": [ { "channel_id": 0, "members": [ { "service": { "rid": "e-3" } } ] } ] },
        |  { "id": "up", "metadata": { "name": "Upstairs" }, "configuration_type": "music",
        |    "channels": [ { "channel_id": 0, "members": [ { "service": { "rid": "e-1" } } ] },
        |                  { "channel_id": 1, "members": [ { "service": { "rid": "e-2" } } ] },
        |                  { "channel_id": 2, "members": [ { "service": { "rid": "e-2" } } ] } ] } ] }""".stripMargin
    val services = """{ "data": [ { "id": "e-1", "owner": { "rid": "d-1" } }, { "id": "e-2", "owner": { "rid": "d-2" } }, { "id": "e-3", "owner": { "rid": "d-3" } } ] }"""
    val rooms =
      """{ "data": [ { "metadata": { "name": "Hall" }, "children": [ { "rid": "d-1" } ] },
        |            { "metadata": { "name": "Bedroom" }, "children": [ { "rid": "d-2" } ] },
        |            { "metadata": { "name": "Lounge" }, "children": [ { "rid": "d-3" } ] } ] }""".stripMargin
    val bridge = Client.fromHttpApp(HttpRoutes.of[IO] {
      case GET -> Root / "clip" / "v2" / "resource" / "entertainment_configuration" => Ok(configurations)
      case GET -> Root / "clip" / "v2" / "resource" / "entertainment"               => Ok(services)
      case GET -> Root / "clip" / "v2" / "resource" / "room"                        => Ok(rooms)
    }.orNotFound)
    new HueClient[IO](creds, bridge).areas.assertEquals(
      List(MusicArea("up", "Upstairs", "music", List("Hall", "Bedroom"), 2), MusicArea("tv", "TV", "screen", List("Lounge"), 1))
    )
  }

  test("starts and stops streaming on an area with the application key") {
    for {
      seen <- Ref.of[IO, List[(String, Option[String])]](Nil)
      bridge = Client.fromHttpApp(HttpRoutes.of[IO] {
        case req @ PUT -> Root / "clip" / "v2" / "resource" / "entertainment_configuration" / "zone-1" =>
          req.bodyText.compile.string.flatMap { text =>
            val action = io.circe.parser.parse(text).flatMap(_.hcursor.downField("action").as[String]).getOrElse("?")
            seen.update(_ :+ (action -> req.headers.get(ci"hue-application-key").map(_.head.value))) *> Ok()
          }
      }.orNotFound)
      hue = new HueClient[IO](creds, bridge)
      _ <- hue.setStreaming("zone-1", active = true) *> hue.setStreaming("zone-1", active = false)
      actions <- seen.get
    } yield assertEquals(actions, List("start" -> Some("app-key"), "stop" -> Some("app-key")))
  }

  test("a bridge that won't start streaming is an error naming the area and the reason") {
    val bridge = Client.fromHttpApp(HttpRoutes.of[IO] {
      case PUT -> Root / "clip" / "v2" / "resource" / "entertainment_configuration" / "zone-1" => Forbidden("not your area")
    }.orNotFound)
    new HueClient[IO](creds, bridge).setStreaming("zone-1", active = true).attempt.map {
      case Left(HueApiError(message)) => assert(message.contains("start streaming on zone-1") && message.contains("403") && message.contains("not your area"), message)
      case other                      => fail(s"expected a HueApiError, got $other")
    }
  }

  test("listing the areas fails with a HueApiError when the bridge refuses or answers in an unexpected shape") {
    List(InternalServerError("busy"), Ok("""{ "nothing": [] }""")).traverse_ { answer =>
      val bridge = Client.fromHttpApp(HttpRoutes.of[IO] { case GET -> Root / "clip" / "v2" / "resource" / _ => answer }.orNotFound)
      new HueClient[IO](creds, bridge).areas.attempt.map(result => assert(result match { case Left(_: HueApiError) => true; case _ => false }, result.toString))
    }
  }
}
