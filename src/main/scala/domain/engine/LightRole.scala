package domain.engine

/**
 * What a light does in the show. `Rhythm` lights pulse with the kick and bass pattern, `Colour` lights glow and move
 * along the palette as the music goes higher or lower, and `Pulse` lights do the classic pulse that travels across the
 * zone.
 */
enum LightRole {
  case Pulse, Rhythm, Colour
}

object LightRole {

  /** Each light's role, repeating `pattern` across `lights` lights in channel order. */
  def assign(pattern: List[LightRole], lights: Int): Vector[LightRole] =
    if (pattern.isEmpty) Vector.fill(lights)(LightRole.Pulse)
    else Vector.tabulate(lights)(i => pattern(i % pattern.size))

  def parse(name: String): Option[LightRole] = name.trim.toLowerCase match {
    case "pulse"            => Some(LightRole.Pulse)
    case "rhythm"           => Some(LightRole.Rhythm)
    case "colour" | "color" => Some(LightRole.Colour)
    case _                  => None
  }
}
