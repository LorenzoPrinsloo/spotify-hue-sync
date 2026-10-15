package settings

import cats.effect.Async
import cats.syntax.all.*
import domain.config.{LightShowConfig, SettingsProblems}
import org.http4s.{HttpRoutes, Request, Response, StaticFile}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl

/**
 * The settings page and its API: read the settings, change or reset the ones for the next song, or take them up
 * straight away with `applyNow`.
 */
object SettingsRoutes {

  def apply[F[_]: Async](live: LiveSettings[F], applyNow: F[Unit]): HttpRoutes[F] = {
    val dsl = Http4sDsl[F]
    import dsl.*

    def page(request: Request[F], name: String): F[Response[F]] =
      StaticFile.fromResource[F](s"/ui/$name", Some(request)).getOrElseF(NotFound())

    HttpRoutes.of[F] {
      case request @ GET -> Root               => page(request, "index.html")
      case request @ GET -> Root / "main.js"   => page(request, "main.js")
      case GET -> Root / "api" / "settings"    => live.view.flatMap(Ok(_))
      case request @ PUT -> Root / "api" / "settings" =>
        request.attemptAs[LightShowConfig].value.flatMap {
          case Left(failure) => BadRequest(SettingsProblems(List(failure.message)))
          case Right(next)   => live.update(next).flatMap(_.fold(BadRequest(_), Ok(_)))
        }
      case POST -> Root / "api" / "settings" / "reset" => live.reset.flatMap(Ok(_))
      case POST -> Root / "api" / "settings" / "apply" => applyNow *> live.view.flatMap(Ok(_))
    }
  }
}
