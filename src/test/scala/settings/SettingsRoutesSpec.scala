package settings

import cats.effect.{IO, Ref}
import domain.config.{BeatSource, LightShowConfig, PulseSettings, SettingsProblems, SettingsView}
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.implicits.*

class SettingsRoutesSpec extends CatsEffectSuite {

  private val fromFile = LightShowConfig(beatSource = BeatSource.Audio)
  private val sharper = fromFile.copy(pulse = PulseSettings().copy(attack = 0.05))

  /** The routes, the settings behind them, and how many times they've asked to apply the settings now. */
  private case class Fixture(app: HttpApp[IO], live: LiveSettings[IO], applied: Ref[IO, Int])

  private def routes: IO[Fixture] =
    for {
      live <- LiveSettings.make[IO](fromFile)
      applied <- Ref.of[IO, Int](0)
    } yield Fixture(SettingsRoutes[IO](live, applied.update(_ + 1)).orNotFound, live, applied)

  test("the settings are read as JSON") {
    routes.flatMap(_.app.run(Request(Method.GET, uri"/api/settings")).flatMap(_.as[SettingsView]))
      .assertEquals(SettingsView(fromFile, fromFile, fromFile))
  }

  test("saving valid settings makes them the pending ones") {
    for {
      fixture <- routes
      response <- fixture.app.run(Request[IO](Method.PUT, uri"/api/settings").withEntity(sharper))
      view <- fixture.live.view
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals(view.pending, sharper)
    }
  }

  test("settings that can't work, or aren't settings at all, are turned down with the reasons") {
    val broken = fromFile.copy(defaultBpm = 0)
    for {
      fixture <- routes
      refused <- fixture.app.run(Request[IO](Method.PUT, uri"/api/settings").withEntity(broken))
      reasons <- refused.as[SettingsProblems]
      garbled <- fixture.app.run(Request[IO](Method.PUT, uri"/api/settings").withEntity("""{ "palette": "rainbow" }""".asJson))
    } yield {
      assertEquals((refused.status, reasons), (Status.BadRequest, SettingsProblems(List("default-bpm must be positive"))))
      assertEquals(garbled.status, Status.BadRequest)
    }
  }

  test("reset and apply now do what they say") {
    for {
      fixture <- routes
      _ <- fixture.live.update(sharper)
      reset <- fixture.app.run(Request(Method.POST, uri"/api/settings/reset")).flatMap(_.as[SettingsView])
      _ <- fixture.app.run(Request(Method.POST, uri"/api/settings/apply"))
      times <- fixture.applied.get
    } yield {
      assertEquals(reset.pending, fromFile)
      assertEquals(times, 1)
    }
  }

  test("the page and its script are served") {
    for {
      fixture <- routes
      page <- fixture.app.run(Request(Method.GET, uri"/"))
      html <- page.bodyText.compile.string
      script <- fixture.app.run(Request(Method.GET, uri"/main.js"))
    } yield {
      assert(html.contains("""<script src="main.js">"""), html)
      assertEquals(script.status, Status.Ok)
      assertEquals(script.contentType.map(_.mediaType), Some(MediaType.application.javascript))
    }
  }
}
