package domain.config

import io.circe.Json
import io.circe.syntax.*

/** Settings written out as lines for `application.conf`, keeping only the ones that differ from the defaults. */
object ConfigSnippet {

  def of(settings: LightShowConfig): String =
    changes(LightShowConfig(), settings) match {
      case Nil     => "# Every light-show setting is at its default"
      case changed => changed.map((path, value) => s"light-show.$path = ${value.noSpaces}").mkString("\n")
    }

  /** The settings `to` sets differently from `from`, by their path in the config file, such as `pulse.attack`. */
  def changes(from: LightShowConfig, to: LightShowConfig): List[(String, Json)] = {
    val before = leaves(from.asJson).toMap
    leaves(to.asJson).filterNot((path, value) => before.get(path).contains(value))
  }

  /** Every setting's path in the config file, in the order the settings are declared. */
  def paths(settings: LightShowConfig): List[String] = leaves(settings.asJson).map(_._1)

  private def leaves(json: Json, path: String = ""): List[(String, Json)] =
    json.asObject.fold(List(path -> json)) { fields =>
      fields.toList.flatMap((key, value) => leaves(value, if (path.isEmpty) kebab(key) else s"$path.${kebab(key)}"))
    }

  private def kebab(field: String): String = field.flatMap(c => if (c.isUpper) s"-${c.toLower}" else c.toString)
}
