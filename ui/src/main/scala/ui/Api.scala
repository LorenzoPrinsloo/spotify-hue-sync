package ui

import com.raquo.laminar.api.L.*
import com.raquo.airstream.web.FetchStream
import domain.config.{LightShowConfig, SettingsProblems, SettingsView}
import domain.engine.RGB
import domain.hue.MusicArea
import io.circe.Decoder
import io.circe.parser.decode
import io.circe.syntax.*
import org.scalajs.dom

/** The app's settings API, as the page sees it. Every call gives either the settings or why it failed. */
object Api {

  private val fetch = FetchStream.withCodec[LightShowConfig, (Int, String)](
    settings => settings.asJson.noSpaces,
    response => EventStream.fromJsPromise(response.text()).map(text => (response.status, text))
  )

  def settings: EventStream[Either[List[String], SettingsView]] = answer(fetch.get("/api/settings"))

  /** The palette of the song playing now, in the order the lights take it; empty before the first song. */
  def palette: EventStream[Either[List[String], List[RGB]]] =
    fetch.get("/api/palette").recoverToEither.map {
      case Right((status, body)) if status < 300 => read[List[RGB]](body)
      case Right((status, _)) => Left(List(s"Couldn't read the song's palette (status $status)"))
      case Left(failure) => Left(List(s"Couldn't reach the app: ${failure.getMessage}"))
    }

  /** The bridge's entertainment areas, music areas first. */
  def areas: EventStream[Either[List[String], List[MusicArea]]] =
    fetch.get("/api/areas").recoverToEither.map {
      case Right((status, body)) if status < 300 => read[List[MusicArea]](body)
      case Right((status, _)) => Left(List(s"Couldn't list the bridge's areas (status $status)"))
      case Left(failure) => Left(List(s"Couldn't reach the app: ${failure.getMessage}"))
    }

  def save(next: LightShowConfig): EventStream[Either[List[String], SettingsView]] =
    answer(fetch.put("/api/settings", _.body(next), _.headers("Content-Type" -> "application/json")))

  def reset: EventStream[Either[List[String], SettingsView]] = answer(fetch.post("/api/settings/reset"))

  def applyNow: EventStream[Either[List[String], SettingsView]] = answer(fetch.post("/api/settings/apply"))

  private def answer(response: EventStream[(Int, String)]): EventStream[Either[List[String], SettingsView]] =
    response.recoverToEither.map {
      case Left(failure) => Left(List(s"Couldn't reach the app: ${failure.getMessage}"))
      case Right((status, body)) if status < 300 => read[SettingsView](body)
      case Right((_, body)) => read[SettingsProblems](body).flatMap(p => Left(p.problems))
    }

  private def read[A: Decoder](body: String): Either[List[String], A] =
    decode[A](body).left.map(err => List(s"Unexpected answer from the app: ${err.getMessage}"))

  /** Copies `text` to the clipboard. */
  def copy(text: String): Unit = { val _ = dom.window.navigator.clipboard.writeText(text) }
}
