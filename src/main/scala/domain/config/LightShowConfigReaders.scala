package domain.config

import domain.engine.{LightRole, PaletteType, RGB}
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert

/** How the `light-show` section of the config file is read, rejecting settings that can't work. */
object LightShowConfigReaders {

  given ConfigReader[PaletteType] = ConfigReader[String].emap { name =>
    PaletteType.parse(name).toRight(CannotConvert(name, "PaletteType", s"expected one of ${PaletteType.names.keys.toList.sorted.mkString(", ")}"))
  }

  given ConfigReader[BeatSource] = ConfigReader[String].emap { name =>
    BeatSource.parse(name).toRight(CannotConvert(name, "BeatSource", "expected spotify or audio"))
  }

  given ConfigReader[LightRole] = ConfigReader[String].emap { name =>
    LightRole.parse(name).toRight(CannotConvert(name, "LightRole", "expected rhythm, colour or pulse"))
  }

  given ConfigReader[RGB] = ConfigReader[String].emap { hex =>
    RGB.fromHex(hex).toRight(CannotConvert(hex, "RGB", "expected a colour like #1e1408"))
  }

  given ConfigReader[PulseSettings] = ConfigReader.derived[PulseSettings]
  given ConfigReader[PauseSettings] = ConfigReader.derived[PauseSettings]
  given ConfigReader[AudioSettings] = ConfigReader.derived[AudioSettings]
  given ConfigReader[SwellSettings] = ConfigReader.derived[SwellSettings]
  given ConfigReader[RhythmSettings] = ConfigReader.derived[RhythmSettings]
  given ConfigReader[ReactiveSettings] = ConfigReader.derived[ReactiveSettings]

  given ConfigReader[LightShowConfig] = ConfigReader.derived[LightShowConfig].emap { c =>
    LightShowConfig.validate(c).left.map(reason => CannotConvert("light-show", "LightShowConfig", reason))
  }
}
