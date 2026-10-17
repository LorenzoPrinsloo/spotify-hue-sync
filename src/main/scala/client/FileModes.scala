package client

import fs2.io.file.{Files, Path, Permissions, PosixPermissions}

/** Reads and sets a file's POSIX permissions. Whether a `set` took effect is only known by reading them back. */
trait FileModes[F[_]] {
  def get(path: Path): F[PosixPermissions]
  def set(path: Path, permissions: PosixPermissions): F[Unit]

  /** The attributes to create a file or directory with so it never exists with looser ones, or None when there are none. */
  def creationModes(permissions: PosixPermissions): Option[Permissions]
}

object FileModes {

  def posix[F[_]: Files]: FileModes[F] = new FileModes[F] {
    def get(path: Path): F[PosixPermissions] = Files[F].getPosixPermissions(path)
    def set(path: Path, permissions: PosixPermissions): F[Unit] = Files[F].setPosixPermissions(path, permissions)
    def creationModes(permissions: PosixPermissions): Option[Permissions] = Some(permissions)
  }
}
