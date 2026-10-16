import cats.effect.{IO, Ref}
import client.HueClient
import domain.hue.{HueCredentials, Zone}
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*

class LightsSpec extends CatsEffectSuite {

  private val areas =
    """{ "data": [ { "id": "up", "metadata": { "name": "Upstairs" }, "configuration_type": "music",
      |              "channels": [ { "channel_id": 0, "members": [] }, { "channel_id": 1, "members": [] } ] } ] }""".stripMargin

  /** A bridge with one area, counting how often its channels are read. */
  private def lights(channelReads: Ref[IO, Int]): IO[Lights[IO]] = {
    val bridge = Client.fromHttpApp(HttpRoutes.of[IO] {
      case GET -> Root / "clip" / "v2" / "resource" / "entertainment_configuration" / "up" =>
        channelReads.update(_ + 1) *> Ok(areas)
      case GET -> Root / "clip" / "v2" / "resource" / "entertainment_configuration" => Ok(areas)
      case GET -> Root / "clip" / "v2" / "resource" / _ => Ok("""{ "data": [] }""")
    }.orNotFound)
    Lights.fromBridge[IO](new HueClient[IO](HueCredentials("10.0.0.2", "key", "00ff"), bridge))
  }

  test("an area's lights are found by its name, ignoring case, and read from the bridge only once") {
    for {
      reads <- Ref.of[IO, Int](0)
      bridge <- lights(reads)
      first <- bridge.zone("upstairs")
      again <- bridge.zone("Upstairs")
      times <- reads.get
      listed <- bridge.areas
    } yield {
      assertEquals((first, again), (Some(Zone("up", List(0, 1))), Some(Zone("up", List(0, 1)))))
      assertEquals(times, 1)
      assertEquals(listed.map(_.name), List("Upstairs"))
    }
  }

  test("an area the bridge doesn't have has no lights") {
    Ref.of[IO, Int](0).flatMap(lights).flatMap(_.zone("Attic")).assertEquals(None)
  }
}
