package client

import cats.Applicative
import cats.effect.Async
import cats.effect.kernel.{Ref, Resource}
import cats.effect.std.Mutex
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.{CopyFlag, CopyFlags, Files, Path, Permissions, PosixPermission, PosixPermissions}
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** The newest refresh token Spotify issued, kept between runs. Problems are logged as WARNs, never raised. */
trait TokenStore[F[_]] {

  /** The token the file held when the store was built, or None when there was none, it couldn't be used or it was forgotten. */
  def load: F[Option[String]]

  /** Atomically replaces the file with `token`, created owner-only, creating its directory owner-only if needed. */
  def save(token: String): F[Unit]

  /** Deletes the saved token, or empties the file when it can't be deleted. */
  def forget: F[Unit]
}

object TokenStore {

  private val ownerOnly = PosixPermissions(PosixPermission.OwnerRead, PosixPermission.OwnerWrite)
  private val ownerDirectory = PosixPermissions(PosixPermission.OwnerRead, PosixPermission.OwnerWrite, PosixPermission.OwnerExecute)

  private def logger[F[_]: Async] = Slf4jLogger.getLoggerFromName[F]("client.TokenStore")

  private def reason(e: Throwable): String = s"${e.getClass.getName}: ${e.getMessage}"

  /** Keeps nothing between runs. */
  def none[F[_]: Applicative]: TokenStore[F] = new TokenStore[F] {
    def load: F[Option[String]] = Applicative[F].pure(None)
    def save(token: String): F[Unit] = Applicative[F].unit
    def forget: F[Unit] = Applicative[F].unit
  }

  /** The file at `location`, or `none` with a WARN when there is no location. */
  def at[F[_]: Async: Files](location: Option[Path]): F[TokenStore[F]] =
    location.fold {
      val problem = TokenFileProblem.NoLocation
      logger[F].warn(problem.context)(problem.message).as(none[F])
    }(path => logger[F].info(Map("path" -> path.toString))("Spotify refresh token file") *> file(path, FileModes.posix[F]))

  /**
   * Reads the file at `path` once, tightening loose permissions through `modes`. Each kind of problem from saving or
   * restricting permissions is logged at most once per store.
   */
  def file[F[_]: Async: Files](path: Path, modes: FileModes[F]): F[TokenStore[F]] =
    file(path, modes, Files[F].move(_, _, CopyFlags(CopyFlag.AtomicMove, CopyFlag.ReplaceExisting)), Files[F].delete(_))

  private[client] def file[F[_]: Async: Files](
    path: Path,
    modes: FileModes[F],
    move: (Path, Path) => F[Unit],
    delete: Path => F[Unit]
  ): F[TokenStore[F]] =
    for {
      reported <- Ref.of[F, Set[String]](Set.empty)
      mutex <- Mutex[F]
      disk = new Disk[F](path, modes, move, delete, reported)
      loaded <- disk.read.flatMap(Ref.of[F, Option[String]](_))
    } yield new TokenStore[F] {
      def load: F[Option[String]] = loaded.get

      def save(token: String): F[Unit] =
        mutex.lock.surround(disk.write(token).handleErrorWith(e => disk.report(TokenFileProblem.Unwritable(path, reason(e)))))

      def forget: F[Unit] = mutex.lock.surround(disk.remove) *> loaded.set(None)
    }

  private val groupAndOthers = 0x3f

  private final class Disk[F[_]: Async: Files](
    path: Path,
    modes: FileModes[F],
    move: (Path, Path) => F[Unit],
    delete: Path => F[Unit],
    reported: Ref[F, Set[String]]
  ) {
    private val target = path.absolute

    def report(problem: TokenFileProblem): F[Unit] =
      reported
        .modify(seen => (seen + problem.message, !seen.contains(problem.message)))
        .flatMap(fresh => logger[F].warn(problem.context)(problem.message).whenA(fresh))

    private def restrict: F[Unit] =
      modes.get(path).attempt.flatMap {
        _.fold(
          _ => report(TokenFileProblem.NoPosixPermissions(path)),
          permissions => tighten.unlessA((permissions.value & groupAndOthers) == 0)
        )
      }

    private def noPosix: F[Unit] = report(TokenFileProblem.NoPosixPermissions(path))

    private def tighten: F[Unit] = (modes.set(path, ownerOnly) *> modes.get(path)).attempt.flatMap {
      _.fold(_ => noPosix, restricted => if (restricted == ownerOnly) report(TokenFileProblem.LoosePermissions(path)) else noPosix)
    }

    def read: F[Option[String]] =
      Files[F].exists(path).ifM(readExisting, Async[F].pure(None))

    private def readExisting: F[Option[String]] =
      Files[F].readUtf8(path).compile.string.attempt.flatMap {
        _.fold(
          e => report(TokenFileProblem.Unreadable(path, reason(e))).as(None),
          contents => TokenFile.parse(path, contents).fold(problem => report(problem).as(None), token => restrict.as(Some(token)))
        )
      }

    private def createWith[A](permissions: PosixPermissions)(create: Option[Permissions] => F[A]): F[A] =
      create(modes.creationModes(permissions)).recoverWith { case _: UnsupportedOperationException =>
        report(TokenFileProblem.NoPosixPermissions(path)) *> create(None)
      }

    private def put(to: Path, text: String): F[Unit] =
      Stream.emit(text).through(Files[F].writeUtf8(to)).compile.drain

    private def putInPlace(text: String): F[Unit] =
      Files[F].exists(target).ifM(Async[F].unit, createWith(ownerOnly)(Files[F].createFile(target, _))) *> put(target, text)

    private def tempFile(directory: Option[Path]): Resource[F, Path] =
      Resource.make(createWith(ownerOnly)(Files[F].createTempFile(directory, ".refresh-token", ".tmp", _)))(temp =>
        Files[F].deleteIfExists(temp).attempt.void
      )

    def write(token: String): F[Unit] = {
      val text = TokenFile.render(token)
      val directory = target.parent
      for {
        _ <- directory.traverse_(d => createWith(ownerDirectory)(Files[F].createDirectories(d, _)))
        _ <- tempFile(directory).use(temp => put(temp, text) *> move(temp, target).handleErrorWith(_ => putInPlace(text)))
        _ <- modes.get(target).attempt.flatMap {
          _.fold(_ => noPosix, permissions => noPosix.unlessA(permissions == ownerOnly))
        }
      } yield ()
    }

    private def truncate: F[Unit] =
      Files[F].exists(target).ifM(Stream.empty.through(Files[F].writeUtf8(target)).compile.drain, Async[F].unit)

    def remove: F[Unit] =
      delete(target).handleErrorWith(_ => truncate).handleErrorWith(e => report(TokenFileProblem.Undeletable(path, reason(e))))
  }
}
