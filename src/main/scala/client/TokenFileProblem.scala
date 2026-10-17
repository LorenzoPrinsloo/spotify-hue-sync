package client

import fs2.io.file.Path

/** Why the refresh token file couldn't be used as it is. Messages name the file and the reason, never the token. */
enum TokenFileProblem derives CanEqual {
  case Unreadable(path: Path, reason: String)
  case Empty(path: Path)
  case Malformed(path: Path)
  case LoosePermissions(path: Path)
  case NoPosixPermissions(path: Path)
  case Unwritable(path: Path, reason: String)
  case Undeletable(path: Path, reason: String)
  case NoLocation

  def message: String = this match {
    case Unreadable(_, _) => "Couldn't read the saved refresh token"
    case Empty(_) => "The saved refresh token file is empty"
    case Malformed(_) => "The saved refresh token file is malformed"
    case LoosePermissions(_) => "The refresh token file was readable by others; restricted it to 0600"
    case NoPosixPermissions(_) => "Couldn't restrict the refresh token file's permissions on this filesystem"
    case Unwritable(_, _) => "Couldn't save the refresh token"
    case Undeletable(_, _) => "Couldn't delete or empty the rejected refresh token file"
    case NoLocation => "No location for the refresh token file; keeping it in memory"
  }

  def context: Map[String, String] = this match {
    case Unreadable(path, reason) => Map("path" -> path.toString, "reason" -> reason)
    case Empty(path) => Map("path" -> path.toString)
    case Malformed(path) => Map("path" -> path.toString)
    case LoosePermissions(path) => Map("path" -> path.toString)
    case NoPosixPermissions(path) => Map("path" -> path.toString)
    case Unwritable(path, reason) => Map("path" -> path.toString, "reason" -> reason)
    case Undeletable(path, reason) => Map("path" -> path.toString, "reason" -> reason)
    case NoLocation => Map.empty
  }
}
