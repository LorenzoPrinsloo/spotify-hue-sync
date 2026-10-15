package domain.hue

import io.circe.Codec

/**
 * An entertainment area on the bridge: the Hue app's music and TV areas. `kind` is the bridge's name for which it is
 * (`music` or `screen`); `rooms` are the rooms its lights are in, and `lights` how many lights it has.
 */
case class MusicArea(id: String, name: String, kind: String, rooms: List[String], lights: Int) derives Codec.AsObject {
  def isMusic: Boolean = kind == "music"
}

object MusicArea {

  /**
   * The area to play on: the one named `name` if given, otherwise the one with id `zoneId`, otherwise the bridge's
   * only music area. Otherwise, why none could be chosen, naming the areas there are.
   */
  def choose(areas: List[MusicArea], name: Option[String], zoneId: Option[String]): Either[String, MusicArea] = {
    def known = if (areas.isEmpty) "the bridge has no entertainment areas" else s"the bridge has ${areas.map(a => s"\"${a.name}\"").mkString(", ")}"
    (name, zoneId) match {
      case (Some(wanted), _) =>
        areas.find(_.name.equalsIgnoreCase(wanted.trim)).toRight(s"No entertainment area is called \"$wanted\": $known")
      case (None, Some(id)) =>
        areas.find(_.id == id).toRight(s"No entertainment area has the id $id: $known")
      case (None, None) =>
        areas.filter(_.isMusic) match {
          case List(only) => Right(only)
          case _          => Left(s"Set light-show.area to the music area to play on: $known")
        }
    }
  }
}
