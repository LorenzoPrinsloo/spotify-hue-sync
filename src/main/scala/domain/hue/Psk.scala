package domain.hue

import pureconfig.ConfigReader
import pureconfig.error.UserValidationFailed
import scodec.bits.ByteVector

/** The Hue entertainment key: 16 bytes. Its `toString` is redacted so it can't leak into logs. */
final case class Psk private (bytes: ByteVector) {
  override def toString: String = "Psk(<redacted>)"
}

object Psk {

  private val Hex32 = "[0-9a-fA-F]{32}".r

  def parse(raw: String): Either[String, Psk] = {
    val trimmed = raw.trim
    if (Hex32.matches(trimmed)) ByteVector.fromHex(trimmed).map(new Psk(_)).toRight(message)
    else Left(message)
  }

  private val message = "HUE_PSK must be 32 hex digits"

  given ConfigReader[Psk] = ConfigReader[String].emap(s => parse(s).left.map(UserValidationFailed(_)))
}
