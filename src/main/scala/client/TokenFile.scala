package client

import fs2.io.file.Path

object TokenFile {

  val EnvVar = "SPOTIFY_TOKEN_FILE"

  private val Name = "spotify-hue-sync/refresh-token"

  def locate(configured: Option[String], env: Map[String, String]): Option[Path] = {
    def set(value: Option[String]): Option[String] = value.map(_.trim).filter(_.nonEmpty)
    def under(dir: Option[String], rest: String): Option[Path] = set(dir).map(d => Path(d) / rest)

    set(env.get(EnvVar))
      .orElse(set(configured))
      .map(Path(_))
      .orElse(under(env.get("XDG_CONFIG_HOME"), Name))
      .orElse(under(env.get("HOME"), s".config/$Name"))
  }

  def parse(path: Path, contents: String): Either[TokenFileProblem, String] =
    if (contents.trim.isEmpty) Left(TokenFileProblem.Empty(path))
    else {
      val token = contents.stripSuffix("\n")
      if (token.nonEmpty && token.forall(c => c >= '\u0021' && c <= '\u007e')) Right(token)
      else Left(TokenFileProblem.Malformed(path))
    }

  def render(token: String): String = token + "\n"
}
