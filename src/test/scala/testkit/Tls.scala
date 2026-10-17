package testkit

import cats.effect.{IO, Ref, Resource}
import com.comcast.ip4s.*
import fs2.io.net.Network
import fs2.io.net.tls.TLSContext
import org.http4s.*
import org.http4s.client.Client
import org.http4s.ember.server.EmberServerBuilder

object Tls {

  private val password = "changeit"

  def selfSigned: IO[TLSContext[IO]] =
    Network[IO].tlsContext.fromKeyStoreResource("self-signed.p12", password.toCharArray, password.toCharArray)

  def server(app: HttpApp[IO]): Resource[IO, (Int, IO[List[Request[IO]]])] =
    for {
      tls <- Resource.eval(selfSigned)
      seen <- Resource.eval(Ref.of[IO, List[Request[IO]]](Nil))
      recording = HttpApp[IO](req => seen.update(req :: _) *> app.run(req))
      server <- EmberServerBuilder.default[IO].withTLS(tls).withHost(ipv4"127.0.0.1").withPort(port"0").withHttpApp(recording).build
    } yield (server.address.getPort, seen.get.map(_.reverse))

  def redirectTo(port: Int)(client: Client[IO]): Client[IO] =
    Client[IO] { req =>
      val authority = Uri.Authority(host = Uri.RegName("localhost"), port = Some(port))
      client.run(req.withUri(req.uri.copy(authority = Some(authority))))
    }
}
