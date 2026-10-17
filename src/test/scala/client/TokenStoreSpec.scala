package client

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import fs2.Stream
import fs2.io.file.{Files, Path, Permissions, PosixPermission, PosixPermissions}
import munit.CatsEffectSuite
import org.scalacheck.rng.Seed
import org.scalacheck.Gen
import testkit.Tls

import java.io.IOException
import scala.jdk.CollectionConverters.*

class TokenStoreSpec extends CatsEffectSuite {

  private val ownerOnly = PosixPermissions(PosixPermission.OwnerRead, PosixPermission.OwnerWrite)
  private val ownerDirectory = PosixPermissions(PosixPermission.OwnerRead, PosixPermission.OwnerWrite, PosixPermission.OwnerExecute)
  private val readableByOthers =
    PosixPermissions(PosixPermission.OwnerRead, PosixPermission.OwnerWrite, PosixPermission.GroupRead, PosixPermission.OthersRead)

  private val real = FileModes.posix[IO]

  private def modes(
    onGet: Path => IO[PosixPermissions] = real.get,
    onSet: (Path, PosixPermissions) => IO[Unit] = real.set,
    creation: PosixPermissions => Option[Permissions] = Some(_)
  ): FileModes[IO] = new FileModes[IO] {
    def get(path: Path): IO[PosixPermissions] = onGet(path)
    def set(path: Path, permissions: PosixPermissions): IO[Unit] = onSet(path, permissions)
    def creationModes(permissions: PosixPermissions): Option[Permissions] = creation(permissions)
  }

  private val cannotRestrict = modes(onGet = _ => IO.pure(readableByOthers), onSet = (_, _) => IO.unit)

  private def rig[A](use: (Path, IO[List[ILoggingEvent]]) => IO[A]): IO[A] =
    (for {
      dir <- Files[IO].tempDirectory
      appender <- Tls.capturedLogs
      _ <- Tls.loggingAt("client", Level.TRACE)
    } yield (dir, IO(Tls.logged(appender)))).use { (dir, logs) => use(dir, logs) }

  private def warnings(logs: IO[List[ILoggingEvent]]): IO[List[ILoggingEvent]] =
    logs.map(_.filter(e => e.getLevel == Level.WARN && e.getLoggerName == "client.TokenStore"))

  private def warningsAbout(logs: IO[List[ILoggingEvent]], text: String): IO[List[ILoggingEvent]] =
    warnings(logs).map(_.filter(_.getFormattedMessage.contains(text)))

  private val looseNote = "restricted it to 0600"
  private val noPosixNote = "Couldn't restrict"

  private def write(path: Path, text: String): IO[Unit] = Stream.emit(text).through(Files[IO].writeUtf8(path)).compile.drain

  private def read(path: Path): IO[String] = Files[IO].readUtf8(path).compile.string

  private def names(dir: Path): IO[List[String]] = Files[IO].list(dir).map(_.fileName.toString).compile.toList.map(_.sorted)

  private def looseFile(path: Path, token: String): IO[Unit] =
    write(path, token + "\n") *> Files[IO].setPosixPermissions(path, readableByOthers)

  test("a file the app creates is 0600 and a directory it creates is 0700") {
    rig { (dir, _) =>
      val path = dir / "config" / "refresh-token"
      for {
        store <- TokenStore.file(path, real)
        _ <- store.save("abc")
        file <- Files[IO].getPosixPermissions(path)
        directory <- Files[IO].getPosixPermissions(dir / "config")
        contents <- read(path)
      } yield {
        assertEquals(file, ownerOnly)
        assertEquals(directory, ownerDirectory)
        assertEquals(contents, "abc\n")
      }
    }
  }

  test("an existing 0644 file ends up 0600 with one LoosePermissions WARN and its token loaded") {
    rig { (dir, logs) =>
      val path = dir / "token"
      for {
        _ <- looseFile(path, "abc")
        store <- TokenStore.file(path, real)
        loaded <- store.load
        permissions <- Files[IO].getPosixPermissions(path)
        warns <- warnings(logs)
      } yield {
        assertEquals(loaded, Some("abc"))
        assertEquals(permissions, ownerOnly)
        assertEquals(warns.map(_.getFormattedMessage).count(_.contains(looseNote)), 1)
        assertEquals(warns.size, 1)
      }
    }
  }

  private val unrestrictable: List[(String, FileModes[IO])] = List(
    "set does nothing" -> cannotRestrict,
    "set throws IOException" -> modes(onGet = _ => IO.pure(readableByOthers), onSet = (_, _) => IO.raiseError(new IOException("denied"))),
    "get throws" -> modes(onGet = _ => IO.raiseError(new IOException("unsupported")))
  )

  unrestrictable.foreach { (name, fake) =>
    test(s"one NoPosixPermissions WARN, no restricted-it WARN and the token still loaded when $name") {
      rig { (dir, logs) =>
        val path = dir / "token"
        for {
          _ <- looseFile(path, "abc")
          store <- TokenStore.file(path, fake)
          loaded <- store.load
          warns <- warnings(logs).map(_.map(_.getFormattedMessage))
        } yield {
          assertEquals(loaded, Some("abc"))
          assertEquals(warns.count(_.contains(noPosixNote)), 1)
          assertEquals(warns.count(_.contains(looseNote)), 0)
        }
      }
    }
  }

  test("saving with no creation attributes writes the token, with one NoPosixPermissions WARN across saves and no Unwritable WARN") {
    rig { (dir, logs) =>
      val path = dir / "token"
      val fake = modes(onGet = _ => IO.pure(readableByOthers), creation = _ => None)
      for {
        store <- TokenStore.file(path, fake)
        _ <- List("one", "two", "three").traverse_(store.save)
        contents <- read(path)
        warns <- warnings(logs).map(_.map(_.getFormattedMessage))
      } yield {
        assertEquals(contents, "three\n")
        assertEquals(warns.count(_.contains(noPosixNote)), 1)
        assertEquals(warns.count(_.contains("Couldn't save")), 0)
      }
    }
  }

  test("a loose file that can't be restricted, then saved to, gives one NoPosixPermissions WARN in total") {
    rig { (dir, logs) =>
      val path = dir / "token"
      for {
        _ <- looseFile(path, "abc")
        store <- TokenStore.file(path, cannotRestrict)
        _ <- store.save("def")
        _ <- store.save("ghi")
        warns <- warnings(logs).map(_.map(_.getFormattedMessage))
      } yield assertEquals(warns.count(_.contains(noPosixNote)), 1)
    }
  }

  test("a move that fails still leaves the new token and a newline in the target, with no temp file left") {
    rig { (dir, logs) =>
      val path = dir / "token"
      for {
        _ <- write(path, "old\n") *> Files[IO].setPosixPermissions(path, ownerOnly)
        store <- TokenStore.file(path, real, (_, _) => IO.raiseError(new IOException("busy")), Files[IO].delete(_))
        _ <- store.save("new")
        contents <- read(path)
        files <- names(dir)
        warns <- warnings(logs)
      } yield {
        assertEquals(contents, "new\n")
        assertEquals(files, List("token"))
        assertEquals(warns, Nil)
      }
    }
  }

  test("a move that fails with no existing target still creates it as 0600") {
    rig { (dir, logs) =>
      val path = dir / "token"
      for {
        store <- TokenStore.file(path, real, (_, _) => IO.raiseError(new IOException("busy")), Files[IO].delete(_))
        _ <- store.save("new")
        contents <- read(path)
        permissions <- Files[IO].getPosixPermissions(path)
        files <- names(dir)
        warns <- warnings(logs)
      } yield {
        assertEquals(contents, "new\n")
        assertEquals(permissions, ownerOnly)
        assertEquals(files, List("token"))
        assertEquals(warns, Nil)
      }
    }
  }

  test("forget deletes the file and load then gives None") {
    rig { (dir, _) =>
      val path = dir / "token"
      for {
        _ <- write(path, "abc\n")
        store <- TokenStore.file(path, real)
        before <- store.load
        _ <- store.forget
        after <- store.load
        exists <- Files[IO].exists(path)
      } yield {
        assertEquals(before, Some("abc"))
        assertEquals(after, None)
        assert(!exists)
      }
    }
  }

  test("a delete that fails truncates the file, with no Undeletable WARN, and a new store loads None with an Empty WARN") {
    rig { (dir, logs) =>
      val path = dir / "token"
      for {
        _ <- write(path, "abc\n")
        store <- TokenStore.file(path, real, (_, _) => IO.unit, _ => IO.raiseError(new IOException("busy")))
        _ <- store.forget
        contents <- read(path)
        undeletable <- warningsAbout(logs, "Couldn't delete or empty")
        again <- TokenStore.file(path, real)
        loaded <- again.load
        empty <- warningsAbout(logs, "is empty")
      } yield {
        assertEquals(contents, "")
        assertEquals(undeletable, Nil)
        assertEquals(loaded, None)
        assertEquals(empty.size, 1)
      }
    }
  }

  test("a delete that fails on a path that is a directory gives exactly one Undeletable WARN") {
    rig { (dir, logs) =>
      val path = dir / "token"
      for {
        _ <- Files[IO].createDirectory(path)
        store <- TokenStore.file(path, real, (_, _) => IO.unit, _ => IO.raiseError(new IOException("busy")))
        _ <- store.forget
        _ <- store.forget
        warns <- warningsAbout(logs, "Couldn't delete or empty")
      } yield {
        assertEquals(warns.size, 1)
        assertEquals(warns.head.getMDCPropertyMap.get("path"), path.toString)
      }
    }
  }

  test("a parent path that is a regular file makes the save unwritable with one WARN naming the path and reason, and no error") {
    rig { (dir, logs) =>
      val blocker = dir / "blocker"
      val path = blocker / "token"
      for {
        _ <- write(blocker, "x")
        store <- TokenStore.file(path, real)
        _ <- List("one", "two", "three").traverse_(store.save)
        warns <- warningsAbout(logs, "Couldn't save")
        loaded <- store.load
      } yield {
        assertEquals(warns.size, 1)
        assertEquals(warns.head.getMDCPropertyMap.get("path"), path.toString)
        assert(Option(warns.head.getMDCPropertyMap.get("reason")).exists(_.nonEmpty))
        assertEquals(loaded, None)
      }
    }
  }

  test("a token path that is a directory is unreadable with one WARN and load gives None") {
    rig { (dir, logs) =>
      val path = dir / "token"
      for {
        _ <- Files[IO].createDirectory(path)
        store <- TokenStore.file(path, real)
        loaded <- store.load
        warns <- warningsAbout(logs, "Couldn't read")
      } yield {
        assertEquals(loaded, None)
        assertEquals(warns.size, 1)
      }
    }
  }

  List(
    "an empty file" -> ("", "is empty"),
    "a malformed file" -> ("two words\n", "is malformed")
  ).foreach { case (name, (contents, note)) =>
    test(s"$name gives one WARN and load gives None") {
      rig { (dir, logs) =>
        val path = dir / "token"
        for {
          _ <- write(path, contents)
          store <- TokenStore.file(path, real)
          loaded <- store.load
          warns <- warnings(logs)
        } yield {
          assertEquals(loaded, None)
          assertEquals(warns.size, 1)
          assert(warns.head.getFormattedMessage.contains(note))
        }
      }
    }
  }

  test("a missing file gives no WARN and load gives None") {
    rig { (dir, logs) =>
      for {
        store <- TokenStore.file(dir / "token", real)
        loaded <- store.load
        warns <- warnings(logs)
      } yield {
        assertEquals(loaded, None)
        assertEquals(warns, Nil)
      }
    }
  }

  test("parallel saves leave exactly one of the tokens and a newline, with no temp files") {
    rig { (dir, logs) =>
      val path = dir / "token"
      val tokens = (1 to 12).map(i => s"token-$i").toList
      for {
        store <- TokenStore.file(path, real)
        _ <- tokens.parTraverse_(store.save)
        contents <- read(path)
        files <- names(dir)
        warns <- warnings(logs)
      } yield {
        assert(tokens.map(_ + "\n").contains(contents), contents)
        assertEquals(files, List("token"))
        assertEquals(warns, Nil)
      }
    }
  }

  test("a store at no location gives one NoLocation WARN and keeps nothing") {
    rig { (_, logs) =>
      for {
        store <- TokenStore.at[IO](None)
        _ <- store.save("abc")
        loaded <- store.load
        _ <- store.forget
        warns <- warnings(logs)
      } yield {
        assertEquals(loaded, None)
        assertEquals(warns.size, 1)
      }
    }
  }

  test("a store at a location logs where it is and loads what was saved by a previous store") {
    rig { (dir, logs) =>
      val path = dir / "token"
      for {
        first <- TokenStore.at[IO](Some(path))
        _ <- first.save("abc")
        second <- TokenStore.at[IO](Some(path))
        loaded <- second.load
        infos <- logs.map(_.filter(e => e.getLevel == Level.INFO && e.getFormattedMessage == "Spotify refresh token file"))
      } yield {
        assertEquals(loaded, Some("abc"))
        assertEquals(infos.size, 2)
        assertEquals(infos.head.getMDCPropertyMap.get("path"), path.toString)
      }
    }
  }

  test("a store that keeps nothing loads None, and saving or forgetting does nothing") {
    val store = TokenStore.none[IO]
    (store.save("abc") *> store.forget *> store.load).assertEquals(None)
  }

  private val tokens: List[String] =
    Gen
      .listOfN(8, Gen.choose(10, 40).flatMap(Gen.stringOfN(_, Gen.alphaNumChar)))
      .pureApply(Gen.Parameters.default, Seed(7))

  private def leaks(token: String, events: List[ILoggingEvent]): List[String] =
    events.flatMap { e =>
      val thrown = Option(e.getThrowableProxy).map(_.getMessage).toList
      e.getFormattedMessage :: e.getMDCPropertyMap.asScala.values.toList ++ thrown
    }.filter(_.contains(token))

  test("no event's message, MDC value or throwable message contains the token in any scenario") {
    tokens.traverse_ { token =>
      rig { (dir, logs) =>
        val failingMove: (Path, Path) => IO[Unit] = (_, _) => IO.raiseError(new IOException("busy"))
        val failingDelete: Path => IO[Unit] = _ => IO.raiseError(new IOException("busy"))
        val loose = dir / "loose"
        val malformed = dir / "malformed"
        val blocker = dir / "blocker"
        val directory = dir / "directory"
        for {
          _ <- looseFile(loose, token)
          looseStore <- TokenStore.file(loose, cannotRestrict)
          _ <- looseStore.save(token)
          _ <- looseStore.forget

          _ <- write(malformed, s"$token with spaces\n")
          _ <- TokenStore.file(malformed, real)

          _ <- write(blocker, token)
          blocked <- TokenStore.file(blocker / "token", real)
          _ <- blocked.save(token)

          moved <- TokenStore.file(dir / "moved", real, failingMove, failingDelete)
          _ <- moved.save(token)
          _ <- moved.forget

          _ <- Files[IO].createDirectory(directory)
          stuck <- TokenStore.file(directory, real, (_, _) => IO.unit, failingDelete)
          _ <- stuck.save(token)
          _ <- stuck.forget

          events <- logs
        } yield assertEquals(leaks(token, events), Nil)
      }
    }
  }
}
