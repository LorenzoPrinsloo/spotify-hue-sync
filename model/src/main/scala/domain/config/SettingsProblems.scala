package domain.config

import io.circe.Codec

/** Why a change to the settings was turned down. */
case class SettingsProblems(problems: List[String]) derives Codec.AsObject
