package settings

import cats.effect.IO
import cats.syntax.all.*
import domain.config.UiConfig
import munit.CatsEffectSuite
import org.http4s.{HttpRoutes, Uri}
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder

class SettingsServerSpec extends CatsEffectSuite {

  private val hello = HttpRoutes.of[IO] { case GET -> Root / "hello" => Ok("hi") }

  test("serves the routes on the configured host and port, here one the system picks") {
    (SettingsServer.resource[IO](UiConfig(host = "127.0.0.1", port = 0), hello), EmberClientBuilder.default[IO].build).tupled.use {
      (server, client) =>
        client.expect[String](Uri.unsafeFromString(s"${server.baseUri}hello"))
    }.assertEquals("hi")
  }

  test("a host or port that isn't one stops the app at startup, saying which") {
    for {
      badHost <- SettingsServer.resource[IO](UiConfig(host = "not a host!"), hello).use_.attempt
      badPort <- SettingsServer.resource[IO](UiConfig(port = 70000), hello).use_.attempt
    } yield {
      assertEquals(badHost.left.map(_.getMessage), Left("ui.host isn't a host: not a host!"))
      assertEquals(badPort.left.map(_.getMessage), Left("ui.port isn't a port: 70000"))
    }
  }
}
