package domain.hue

import munit.FunSuite

class MusicAreaSpec extends FunSuite {

  private val upstairs = MusicArea("a-1", "Muziekruimte Boven", "music", List("Gang"), 6)
  private val downstairs = MusicArea("a-2", "Muziekruimte Main", "music", List("Woonkamer"), 7)
  private val tv = MusicArea("a-3", "TV-ruimte", "screen", List("Woonkamer"), 2)

  test("an area is chosen by name, ignoring case, before an id") {
    assertEquals(MusicArea.choose(List(upstairs, downstairs), Some("muziekruimte main"), Some("a-1")), Right(downstairs))
  }

  test("without a name, by the id from hue.zone-id") {
    assertEquals(MusicArea.choose(List(upstairs, downstairs), None, Some("a-1")), Right(upstairs))
  }

  test("without either, the only music area, whatever screen areas there are") {
    assertEquals(MusicArea.choose(List(upstairs, tv), None, None), Right(upstairs))
  }

  test("when none can be chosen, the reason lists the areas there are") {
    assertEquals(
      MusicArea.choose(List(upstairs, downstairs), Some("Attic"), None),
      Left("No entertainment area is called \"Attic\": the bridge has \"Muziekruimte Boven\", \"Muziekruimte Main\"")
    )
    assertEquals(
      MusicArea.choose(List(upstairs, downstairs), None, None),
      Left("Set light-show.area to the music area to play on: the bridge has \"Muziekruimte Boven\", \"Muziekruimte Main\"")
    )
    assertEquals(MusicArea.choose(Nil, None, Some("gone")), Left("No entertainment area has the id gone: the bridge has no entertainment areas"))
  }
}
