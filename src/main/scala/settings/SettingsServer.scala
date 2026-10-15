package settings

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import domain.config.UiConfig
import fs2.io.net.Network
import org.http4s.HttpRoutes
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server

/** Serves the settings page where `ui` says, failing at startup if its host or port isn't valid. */
object SettingsServer {

  def resource[F[_]: Async: Network](ui: UiConfig, routes: HttpRoutes[F]): Resource[F, Server] =
    for {
      host <- Resource.eval(Host.fromString(ui.host).liftTo[F](new IllegalArgumentException(s"ui.host isn't a host: ${ui.host}")))
      port <- Resource.eval(Port.fromInt(ui.port).liftTo[F](new IllegalArgumentException(s"ui.port isn't a port: ${ui.port}")))
      server <- EmberServerBuilder.default[F].withHost(host).withPort(port).withHttpApp(routes.orNotFound).build
    } yield server
}
