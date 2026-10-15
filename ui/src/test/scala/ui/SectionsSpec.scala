package ui

import domain.config.{BeatSource, ConfigSnippet, LightShowConfig}
import domain.engine.LightRole

class SectionsSpec extends munit.FunSuite {

  private val defaults = LightShowConfig()
  private val sliders = Sections.all.flatMap(_.sliders)

  test("every slider changes its own setting, and only that one, under the name the config file uses") {
    sliders.foreach { s =>
      val other = if (s.get(defaults) == s.max) s.min else s.max
      assertEquals(ConfigSnippet.changes(defaults, s.set(defaults, other)).map(_._1), List(s.key), s.label)
    }
  }

  test("every slider reads back what it set, to its step") {
    sliders.foreach { s =>
      List(s.min, (s.min + s.max) / 2, s.max).foreach { v =>
        assertEqualsDouble(s.get(s.set(defaults, v)), v, s.step / 2, s.label)
      }
    }
  }

  test("every default lies within its slider's range, so the page can show it") {
    sliders.foreach(s => assert(s.get(defaults) >= s.min && s.get(defaults) <= s.max, s"${s.label}: ${s.get(defaults)}"))
  }

  test("no setting is on the page twice") {
    assertEquals(sliders.map(_.key).distinct, sliders.map(_.key))
  }

  test("every setting is on the page exactly once") {
    val shown = Sections.all.flatMap(section => section.controls.flatMap(_.keys) ++ section.sliders.map(_.key))
    assertEquals(shown.sorted, ConfigSnippet.paths(defaults).sorted)
  }

  test("a section is dimmed exactly when no light plays the roles it's for, or the show isn't reacting") {
    val listening = defaults.copy(beatSource = BeatSource.Audio)
    def withRoles(roles: LightRole*) = listening.copy(reactive = listening.reactive.copy(roles = roles.toList))
    def unused(c: LightShowConfig) = Sections.all.filter(_.unused(c).isDefined).map(_.title)
    val cases = List(
      withRoles(LightRole.Rhythm, LightRole.Colour) -> List("Pulse lights"),
      withRoles(LightRole.Pulse) -> List("Colour lights"),
      withRoles(LightRole.Rhythm) -> List("Pulse lights", "Colour lights", "Sparkles: colour and pulse lights"),
      withRoles(LightRole.Colour) -> List("Rhythm and pulse lights", "Pulse lights"),
      listening.copy(reactive = listening.reactive.copy(enabled = false)) -> List("Colour lights", "Sparkles: colour and pulse lights"),
      defaults -> List("Colour lights", "Sparkles: colour and pulse lights")
    )
    cases.foreach((settings, expected) => assertEquals(unused(settings), expected, settings.reactive.roles.toString))
  }
}
