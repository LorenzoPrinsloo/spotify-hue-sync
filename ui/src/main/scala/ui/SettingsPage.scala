package ui

import com.raquo.laminar.api.L.*
import domain.config.{ConfigSnippet, LightShowConfig, SettingsView}
import domain.engine.{LightRole, PaletteType, RGB}
import domain.hue.MusicArea

/**
 * The settings page. Edits are saved a moment after the last change, as the settings for the next song; the page
 * shows which are still waiting, and can take them up now, reset them to the config file, or copy them as config.
 */
final class SettingsPage {

  private val server = Var(Option.empty[SettingsView])
  private val draft = Var(Option.empty[LightShowConfig])
  private val serverProblems = Var(List.empty[String])
  private val areas = Var(List.empty[MusicArea])
  private val songPalette = Var(List.empty[RGB])

  private def edit(change: LightShowConfig => LightShowConfig): Unit = draft.update(_.map(change))

  private val localProblems: Signal[List[String]] = draft.signal.map(_.fold(Nil)(LightShowConfig.problems))

  /** Settings whose new value is waiting for the next song, by their path in the config file. */
  private val waiting: Signal[Set[String]] =
    server.signal.combineWith(draft.signal).map {
      case (Some(view), Some(edited)) => ConfigSnippet.changes(view.applied, edited).map(_._1).toSet
      case _ => Set.empty
    }

  private def received(answer: Either[List[String], SettingsView]): Unit =
    answer match {
      case Right(view) => server.set(Some(view)); serverProblems.set(Nil)
      case Left(found) => serverProblems.set(found)
    }

  private def loaded(answer: Either[List[String], SettingsView]): Unit = {
    received(answer)
    answer.foreach(view => draft.set(Some(view.pending)))
  }

  def view: HtmlElement =
    div(
      cls := "page",
      Api.settings --> (answer => loaded(answer)),
      Api.areas --> {
        case Right(found) => areas.set(found)
        case Left(failed) => serverProblems.set(failed)
      },
      EventStream.periodic(2000).drop(1).flatMapSwitch(_ => Api.settings) --> (answer => received(answer)),
      EventStream.periodic(2000).flatMapSwitch(_ => Api.palette) --> (answer => answer.foreach(songPalette.set)),
      draft.signal.changes.debounce(300).collect { case Some(edited) if LightShowConfig.problems(edited).isEmpty => edited }
        .flatMapSwitch(Api.save) --> (answer => received(answer)),
      headerTag(
        h1("Light show settings"),
        p("Changes take effect when the next song starts and last until the app stops. Your config file isn't changed."),
        div(
          cls := "actions",
          button("Apply now", onClick.flatMapStream(_ => Api.applyNow) --> (answer => received(answer))),
          button("Reset to config file", onClick.flatMapStream(_ => Api.reset) --> (answer => loaded(answer)))
        ),
        p(
          cls := "status",
          child.text <-- waiting.map(paths =>
            if (paths.isEmpty) "The lights are playing these settings."
            else s"${paths.size} ${if (paths.size == 1) "change waits" else "changes wait"} for the next song."
          )
        ),
        ul(cls := "problems", children <-- localProblems.combineWith(serverProblems.signal).map((a, b) => (a ++ b).distinct.map(li(_))))
      ),
      child <-- draft.signal.map(_.isDefined).distinct.map { ready =>
        if (!ready) p("Loading the settings…")
        else div(cls := "sections", Sections.all.map(section), snippet)
      }
    )

  private def marked(key: String): Modifier[HtmlElement] = cls("waiting") <-- waiting.map(_.contains(key))

  private def section(s: Section): HtmlElement = {
    val unused = draft.signal.map(_.flatMap(s.unused))
    div(
      cls := "section",
      cls("unused") <-- unused.map(_.isDefined),
      h2(s.title),
      p(cls := "intro", s.intro),
      child.maybe <-- unused.map(_.map(reason => p(cls := "unused-note", reason))),
      s.controls.map(control),
      s.sliders.map(slider)
    )
  }

  private def slider(s: Slider): HtmlElement = {
    val current = draft.signal.map(_.fold(s.min)(s.get))
    val decimals = if (s.step >= 1) 0 else if (s.step >= 0.1) 1 else 2
    div(
      cls := "field",
      marked(s.key),
      label(span(s.label), span(cls := "value", child.text <-- current.map(v => s"%.${decimals}f".format(v)))),
      input(
        typ := "range",
        minAttr := s.min.toString,
        maxAttr := s.max.toString,
        stepAttr := s.step.toString,
        controlled(value <-- current.map(_.toString), onInput.mapToValue --> (v => v.toDoubleOption.foreach(d => edit(c => s.set(c, d)))))
      ),
      p(cls := "help", s.help)
    )
  }

  private def control(c: Control): HtmlElement = c match {
    case Control.Area => areaChoice
    case Control.Roles => roles
    case Control.Reactions => reactionsSwitch
    case Control.Palette => paletteChoice
    case Control.SongPalette => songColours
    case Control.RestingColour => restingColour
    case Control.FixedSettings => fixedSettings
  }

  private val rolesNow: Signal[List[LightRole]] = draft.signal.map(_.fold(Nil)(_.reactive.roles))

  private def setRoles(change: List[LightRole] => List[LightRole]): Unit =
    edit(c => c.copy(reactive = c.reactive.copy(roles = change(c.reactive.roles))))

  private def roles: HtmlElement =
    div(
      cls := "field",
      marked("reactive.roles"),
      label(span("Roles, in channel order")),
      div(
        cls := "roles",
        children <-- rolesNow.splitByIndex { (index, _, role) =>
          div(
            cls := "role",
            span(cls := "index", s"${index + 1}"),
            select(
              controlled(value <-- role.map(_.name),
                onChange.mapToValue --> (name => LightRole.parse(name).foreach(r => setRoles(_.updated(index, r))))),
              LightRole.values.toList.map(r => option(value := r.name, r.name))
            ),
            button("Remove", disabled <-- rolesNow.map(_.size <= 1), onClick --> (_ => setRoles(_.patch(index, Nil, 1))))
          )
        },
        button("Add a role", onClick --> (_ => setRoles(_ :+ LightRole.Colour)))
      ),
      p(cls := "help", "Rhythm lights pulse with the kick pattern, colour lights swell and follow the pitch, pulse lights do the classic wave.")
    )

  private def reactionsSwitch: HtmlElement =
    div(
      cls := "field",
      marked("reactive.enabled"),
      label(
        input(
          typ := "checkbox",
          controlled(checked <-- draft.signal.map(_.exists(_.reactive.enabled)),
            onClick.mapToChecked --> (on => edit(c => c.copy(reactive = c.reactive.copy(enabled = on)))))
        ),
        span("React to the music (and use the light roles)")
      )
    )

  private def describe(area: MusicArea): String = {
    val kind = if (area.isMusic) "" else s", ${area.kind} area"
    s"${area.name}: ${area.rooms.mkString(", ")} (${area.lights} ${if (area.lights == 1) "light" else "lights"}$kind)"
  }

  private def areaChoice: HtmlElement =
    div(
      cls := "field",
      marked("area"),
      label(span("Area")),
      // Built once the areas arrive, so the current area is among the options when its value is set.
      child <-- areas.signal.map { found =>
        select(
          controlled(
            value <-- draft.signal.map(_.flatMap(_.area).getOrElse("")),
            onChange.mapToValue --> (name => edit(_.copy(area = Some(name))))
          ),
          found.map(a => option(value := a.name, describe(a)))
        )
      }
    )

  private def paletteChoice: HtmlElement =
    div(
      cls := "field",
      marked("palette"),
      label(span("Palette")),
      select(
        controlled(
          value <-- draft.signal.map(_.fold("")(_.palette.name)),
          onChange.mapToValue --> (name => PaletteType.parse(name).foreach(t => edit(_.copy(palette = t))))
        ),
        PaletteType.names.keys.toList.sorted.map(name => option(value := name, name))
      ),
      p(cls := "help", "Each new song gets a new palette of this kind.")
    )

  private def songColours: HtmlElement =
    div(
      cls := "field",
      label(span("This song's palette")),
      div(
        cls := "swatches",
        children <-- songPalette.signal.map {
          case Nil => List(span(cls := "help", "Shown once a song starts."))
          case colours => colours.map(c => span(cls := "swatch", backgroundColor := c.hex, title := c.hex))
        }
      ),
      p(cls := "help", "Light 1 takes the first colour, light 2 the second, and so on, starting again after the last.")
    )

  private def restingColour: HtmlElement =
    div(
      cls := "field",
      marked("pause.resting-colour"),
      label(
        span("Resting colour"),
        input(
          typ := "color",
          controlled(
            value <-- draft.signal.map(_.fold("#000000")(_.pause.restingColour.hex)),
            onInput.mapToValue --> (hex => RGB.fromHex(hex).foreach(colour => edit(c => c.copy(pause = c.pause.copy(restingColour = colour)))))
          )
        )
      ),
      p(cls := "help", "What the lights show while nothing plays.")
    )

  private def fixedSettings: HtmlElement =
    p(
      cls := "help",
      child.text <-- server.signal.map(_.fold("") { view =>
        val f = view.fromFile
        s"Beat source ${f.beatSource.name}, audio input ${f.audio.device}, a frame every ${f.frameIntervalMs} ms: these change only when the app restarts."
      })
    )

  private def snippet: HtmlElement = {
    val text = draft.signal.map(_.fold("")(ConfigSnippet.of))
    div(
      cls := "section",
      h2("Copy as config"),
      p(cls := "intro", "Paste these lines into application.conf to keep the current settings."),
      textArea(readOnly := true, rows := 8, value <-- text),
      button("Copy", onClick.compose(_.sample(text)) --> (t => Api.copy(t)))
    )
  }
}
