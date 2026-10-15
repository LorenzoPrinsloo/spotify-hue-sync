package domain.engine

import io.circe.{Codec, Decoder, Encoder}

enum PaletteType derives CanEqual:
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

  private val nameOf: Map[PaletteType, String] = names.map(_.swap)

  extension (p: PaletteType)
    /** How it's written in the config file, such as `analogous-complementary-interspersed`. */
    def name: String = nameOf.getOrElse(p, p.toString.toLowerCase)

  given Codec[PaletteType] = Codec.from(
    Decoder.decodeString.emap(n => parse(n).toRight(s"expected one of ${names.keys.toList.sorted.mkString(", ")}, not $n")),
    Encoder.encodeString.contramap(_.name)
  )
