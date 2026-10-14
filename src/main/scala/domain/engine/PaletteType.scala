package domain.engine

enum PaletteType:
  case Complementary
  case Monochromatic
  case Analogous
  case Triadic
  case Tetradic
  case AnalogousComplementary(order: Order)

object PaletteType:
  val names: Map[String, PaletteType] = Map(
    "analogous" -> Analogous,
    "complementary" -> Complementary,
    "monochromatic" -> Monochromatic,
    "triadic" -> Triadic,
    "tetradic" -> Tetradic,
    "analogous-complementary" -> AnalogousComplementary(Order.Flat),
    "analogous-complementary-interspersed" -> AnalogousComplementary(Order.Interspersed)
  )

  def parse(name: String): Option[PaletteType] = names.get(name.trim.toLowerCase)
