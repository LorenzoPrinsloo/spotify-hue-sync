package client

import cats.Applicative
import cats.effect.{Async, Ref, Sync}
import cats.syntax.all.*
import domain.engine.{CoverColour, RGB}
import org.http4s.{Method, Request, Uri}
import org.http4s.client.Client
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import scala.util.control.NonFatal

/** Each album cover's base colour, read once per cover URL and remembered until the app stops. */
trait AlbumCovers[F[_]] {

  /** The cover's base colour, or None when it has no vivid colour. Raises a `CoverError` when it can't be fetched or read. */
  def baseColour(url: String): F[Option[RGB]]
}

object AlbumCovers {

  /** The longest side, in pixels, a cover is scaled down to before it's read. */
  val MaxSide: Int = 64

  /** Never has a colour, so every song gets a random palette. */
  def none[F[_]: Applicative]: AlbumCovers[F] = new AlbumCovers[F] {
    def baseColour(url: String): F[Option[RGB]] = Applicative[F].pure(None)
  }

  /** Fetches covers with `client`, remembering each one read by its URL; failures aren't remembered. */
  def fromHttp[F[_]: Async](client: Client[F]): F[AlbumCovers[F]] =
    Ref.of[F, Map[String, Option[RGB]]](Map.empty).map { cache =>
      new AlbumCovers[F] {
        private val logger = Slf4jLogger.getLogger[F]

        def baseColour(url: String): F[Option[RGB]] =
          cache.get.flatMap(_.get(url).fold(read(url))(Async[F].pure))

        private def read(url: String): F[Option[RGB]] =
          for {
            bytes <- fetch(url)
            px <- pixels[F](url, bytes)
            colour = CoverColour.base(px)
            _ <- cache.update(_ + (url -> colour))
            _ <- logger.debug(
              Map("cover_url" -> url, "base" -> colour.fold("none")(_.hex), "pixels" -> px.size.toString)
            )("Read album cover colour")
          } yield colour

        private def fetch(url: String): F[Array[Byte]] =
          Uri.fromString(url).fold(
            err => Async[F].raiseError(CoverError.Unreachable(url, err)),
            uri =>
              client
                .run(Request[F](Method.GET, uri))
                .use { response =>
                  if (!response.status.isSuccess) Async[F].raiseError[Array[Byte]](CoverError.Refused(url, response.status))
                  else response.body.compile.to(Array)
                }
                .handleErrorWith {
                  case e: CoverError => Async[F].raiseError(e)
                  case NonFatal(err) => Async[F].raiseError(CoverError.Unreachable(url, err))
                }
          )
      }
    }

  /**
   * The image's pixels, scaled down to at most `MaxSide` by `MaxSide`. Decoding can be interrupted. Raises
   * `CoverError.Unreadable` if `bytes` isn't an image.
   */
  def pixels[F[_]: Sync](url: String, bytes: Array[Byte]): F[Vector[RGB]] =
    Sync[F]
      .interruptible(Option(ImageIO.read(new ByteArrayInputStream(bytes))))
      .handleErrorWith { case NonFatal(_) => Sync[F].pure(None) }
      .flatMap {
        case Some(img) if img.getWidth > 0 && img.getHeight > 0 =>
          val (w, h) = (img.getWidth, img.getHeight)
          val (tw, th) = (math.min(w, MaxSide), math.min(h, MaxSide))
          val sampled = for {
            y <- 0 until th
            x <- 0 until tw
            argb = img.getRGB(x * w / tw, y * h / th)
            c <- RGB.safe((argb >> 16) & 0xff, (argb >> 8) & 0xff, argb & 0xff)
          } yield c
          Sync[F].pure(sampled.toVector)
        case _ => Sync[F].raiseError(CoverError.Unreadable(url))
      }
}
