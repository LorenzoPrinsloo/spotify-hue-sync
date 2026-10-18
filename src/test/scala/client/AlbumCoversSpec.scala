package client

import cats.effect.{IO, Ref}
import domain.engine.RGB
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import testkit.{Colours, Covers}

import scala.concurrent.duration.*

class AlbumCoversSpec extends CatsEffectSuite {

  private val red = RGB.safe(220, 20, 20).get
  private val blue = RGB.safe(20, 20, 220).get
  private val grey = RGB.safe(128, 128, 128).get

  private val redPng = Covers.png(red -> 0.8, grey -> 0.2)
  private val bluePng = Covers.png(blue -> 0.8, grey -> 0.2)
  private val greyPng = Covers.png(grey -> 1.0)

  private def covers: IO[(AlbumCovers[IO], Ref[IO, Map[String, Int]])] =
    for {
      hits <- Ref.of[IO, Map[String, Int]](Map.empty)
      count = (name: String) => hits.update(m => m.updated(name, m.getOrElse(name, 0) + 1))
      app = HttpRoutes
        .of[IO] {
          case GET -> Root / "red" => count("red") *> Ok(redPng)
          case GET -> Root / "blue" => count("blue") *> Ok(bluePng)
          case GET -> Root / "grey" => count("grey") *> Ok(greyPng)
          case GET -> Root / "missing" => count("missing") *> NotFound()
          case GET -> Root / "broken" => count("broken") *> InternalServerError()
          case GET -> Root / "garbage" => count("garbage") *> Ok(Array[Byte](1, 2, 3, 4))
          case GET -> Root / "down" => count("down") *> IO.raiseError(new java.net.ConnectException("down"))
        }
        .orNotFound
      c <- AlbumCovers.fromHttp[IO](Client.fromHttpApp(app))
    } yield (c, hits)

  private def failure(c: AlbumCovers[IO], name: String): IO[Throwable] =
    c.baseColour(s"https://covers.test/$name").attempt.map(_.left.getOrElse(fail(s"$name did not fail")))

  test("a mostly red cover gives a red base colour") {
    covers.flatMap(_._1.baseColour("https://covers.test/red")).map { base =>
      assert(base.exists(b => Colours.hueDistance(Colours.hue(b), Colours.hue(red)) < 0.03), base.toString)
    }
  }

  test("a mostly blue cover gives a blue base colour") {
    covers.flatMap(_._1.baseColour("https://covers.test/blue")).map { base =>
      assert(base.exists(b => Colours.hueDistance(Colours.hue(b), Colours.hue(blue)) < 0.03), base.toString)
    }
  }

  test("an all grey cover has no base colour") {
    covers.flatMap(_._1.baseColour("https://covers.test/grey")).assertEquals(None)
  }

  test("a 404 raises Refused with that status") {
    covers.flatMap(c => failure(c._1, "missing")).assertEquals(CoverError.Refused("https://covers.test/missing", Status.NotFound))
  }

  test("a 500 raises Refused with that status") {
    covers
      .flatMap(c => failure(c._1, "broken"))
      .assertEquals(CoverError.Refused("https://covers.test/broken", Status.InternalServerError))
  }

  test("garbage bytes raise Unreadable") {
    covers.flatMap(c => failure(c._1, "garbage")).assertEquals(CoverError.Unreadable("https://covers.test/garbage"))
  }

  test("a connection error raises Unreachable") {
    covers.flatMap(c => failure(c._1, "down")).map {
      case _: CoverError.Unreachable => ()
      case e => fail(e.toString)
    }
  }

  test("an unparseable URL raises Unreachable") {
    covers.flatMap(_._1.baseColour("http://bad host/").attempt).map {
      case Left(_: CoverError.Unreachable) => ()
      case r => fail(r.toString)
    }
  }

  test("each error names its reason") {
    val err = new RuntimeException("x")
    assertEquals(CoverError.Refused("u", Status.NotFound).reason, "refused")
    assertEquals(CoverError.Unreachable("u", err).reason, "unreachable")
    assertEquals(CoverError.Unreadable("u").reason, "unreadable")
    assertEquals(CoverError.TimedOut("u", 2.seconds).reason, "timed_out")
  }

  test("a repeated call for the same URL makes no second request") {
    covers.flatMap { (c, hits) =>
      for {
        a <- c.baseColour("https://covers.test/red")
        b <- c.baseColour("https://covers.test/red")
        n <- hits.get
      } yield {
        assertEquals(a, b)
        assertEquals(n("red"), 1)
      }
    }
  }

  test("a repeated call after a None result makes no second request") {
    covers.flatMap { (c, hits) =>
      for {
        a <- c.baseColour("https://covers.test/grey")
        b <- c.baseColour("https://covers.test/grey")
        n <- hits.get
      } yield {
        assertEquals((a, b), (None, None))
        assertEquals(n("grey"), 1)
      }
    }
  }

  test("a failed URL is requested again on the next call") {
    covers.flatMap { (c, hits) =>
      for {
        _ <- c.baseColour("https://covers.test/missing").attempt
        _ <- c.baseColour("https://covers.test/missing").attempt
        n <- hits.get
      } yield assertEquals(n("missing"), 2)
    }
  }

  test("pixels scales a 200 by 100 image down to at most 64 by 64") {
    AlbumCovers.pixels[IO]("u", Covers.png(200, 100, red -> 1.0)).map { px =>
      assert(px.nonEmpty && px.size <= 64 * 64, px.size.toString)
    }
  }

  test("pixels raises Unreadable for garbage") {
    AlbumCovers.pixels[IO]("u", Array[Byte](9, 9, 9)).attempt.assertEquals(Left(CoverError.Unreadable("u")))
  }

  test("none never has a base colour") {
    AlbumCovers.none[IO].baseColour("https://covers.test/red").assertEquals(None)
  }
}
