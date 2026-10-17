package client

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import fs2.io.net.Network
import fs2.io.net.tls.TLSContext
import org.http4s.client.Client
import org.http4s.client.middleware.FollowRedirect
import org.http4s.ember.client.EmberClientBuilder

final case class HttpClients[F[_]](spotify: Client[F], bridge: Client[F])

object HttpClients {

  def resource[F[_]: Async: Network]: Resource[F, HttpClients[F]] =
    Resource.eval(Network[F].tlsContext.system).flatMap(trusting[F])

  def trusting[F[_]: Async: Network](trusted: TLSContext[F]): Resource[F, HttpClients[F]] = {
    val spotify = EmberClientBuilder.default[F].withTLSContext(trusted).build.map(FollowRedirect(2)(_))

    /** The bridge serves a self-signed certificate. */
    val bridge = Resource.eval(Network[F].tlsContext.insecure).flatMap { insecure =>
      EmberClientBuilder.default[F].withTLSContext(insecure).build.map(FollowRedirect(2)(_))
    }
    (spotify, bridge).mapN(HttpClients(_, _))
  }
}
