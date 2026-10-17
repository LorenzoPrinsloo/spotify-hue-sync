package client

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import testkit.Tls

import javax.net.ssl.SSLException
import scala.concurrent.duration.*

class HttpClientsSpec extends CatsEffectSuite {

  private val ok = HttpApp[IO](_ => Ok("hello"))

  private def causes(e: Throwable): List[Throwable] =
    Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(20).toList

  private def get(client: Client[IO], port: Int): IO[Status] =
    Tls.redirectTo(port)(client).status(Request[IO](Method.GET, uri"https://accounts.spotify.com/authorize"))

  test("the Spotify client rejects a self-signed certificate and sends nothing") {
    Tls.server(ok).use { (port, seen) =>
      HttpClients.resource[IO].use(c => get(c.spotify, port)).attempt.timeout(10.seconds).flatMap { result =>
        seen.map { requests =>
          assert(result.left.exists(causes(_).exists({
              case _: SSLException => true
              case _ => false
            })), result.toString)
          assertEquals(requests.size, 0)
        }
      }
    }
  }

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
