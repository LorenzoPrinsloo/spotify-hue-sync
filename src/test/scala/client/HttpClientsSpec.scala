package client

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import testkit.Tls

import scala.concurrent.duration.*

class HttpClientsSpec extends CatsEffectSuite {

  private val ok = HttpApp[IO](_ => Ok("hello"))

  private def get(client: Client[IO], port: Int): IO[Status] =
    Tls.redirectTo(port)(client).status(Request[IO](Method.GET, uri"https://accounts.spotify.com/authorize"))

  test("the Spotify client accepts a certificate it was told to trust") {
    Tls.server(ok).use { (port, _) =>
      Tls.selfSigned.flatMap(tls => HttpClients.trusting(tls).use(c => get(c.spotify, port)).timeout(10.seconds))
    }.assertEquals(Status.Ok)
  }

  test("the bridge client accepts a self-signed certificate") {
    Tls.server(ok).use { (port, _) =>
      HttpClients.resource[IO].use(c => get(c.bridge, port)).timeout(10.seconds)
    }.assertEquals(Status.Ok)
  }
}
