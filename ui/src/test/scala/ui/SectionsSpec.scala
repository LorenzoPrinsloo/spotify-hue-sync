package ui

import domain.config.{ConfigSnippet, LightShowConfig}

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
}
