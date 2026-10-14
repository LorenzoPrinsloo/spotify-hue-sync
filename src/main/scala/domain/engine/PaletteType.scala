package domain.engine

enum PaletteType:
  case Complementary
  case Monochromatic
  case Analogous
  case Triadic
  case Tetradic
  case AnalogousComplementary(order: Order)