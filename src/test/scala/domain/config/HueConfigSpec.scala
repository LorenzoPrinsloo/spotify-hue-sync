package domain.config

import cats.effect.IO
import munit.CatsEffectSuite
import pureconfig.ConfigSource
import pureconfig.error.ConfigReaderException
import scodec.bits.ByteVector

class HueConfigSpec extends CatsEffectSuite {

  private val digits = "0123456789" * 4
  private val key = digits.take(32)

  private def source(psk: String): ConfigSource =
    ConfigSource
      .string(s"""spotify { client-id = "id", client-secret = "secret", refresh-token = "refresh" }
                 |hue { creds { address = "10.0.0.2", username = "user", psk = ${quoted(psk)} } }
                 |""".stripMargin)
      .withFallback(ConfigSource.resources("reference.conf"))

  private def quoted(raw: String): String =
    "\"" + raw.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + "\""

  private def pskOf(raw: String): Either[String, ByteVector] =
    source(raw).load[AppConfig].left.map(_.prettyPrint()).map(_.hue.creds.psk.bytes)

  private val rejected: List[(String, String)] = List(
    "empty" -> "",
    "31 digits" -> digits.take(31),
    "33 digits" -> digits.take(33),
    "non-hex character" -> (digits.take(15) + "g" + digits.take(16)),
    "0x prefix" -> ("0x" + digits.take(30)),
    "0x prefix on 32 digits" -> ("0x" + key),
    "quote inside" -> (digits.take(15) + "\"" + digits.take(16)),
    "space inside" -> (digits.take(15) + " " + digits.take(16))
  )

  test("32 hex digits in upper, lower or mixed case are accepted and decode to the same bytes") {
    val lower = "00112233445566778899aabbccddeeff"
    val mixed = "00112233445566778899AaBbCcDdEeFf"
    val expected = Right(ByteVector.fromValidHex(lower))
    assertEquals(pskOf(lower), expected)
    assertEquals(pskOf(lower.toUpperCase), expected)
    assertEquals(pskOf(mixed), expected)
  }

  test("leading or trailing spaces and a newline are accepted") {
    val expected = Right(ByteVector.fromValidHex(key))
    assertEquals(pskOf("  " + key), expected)
    assertEquals(pskOf(key + "  "), expected)
    assertEquals(pskOf(key + "\n"), expected)
  }

  rejected.foreach { (name, raw) =>
    test(s"a key with $name is rejected when the config loads, naming HUE_PSK without the value") {
      val failure = source(raw).load[AppConfig].left.map(_.prettyPrint())
      val pretty = failure.swap.getOrElse(fail("expected a failure"))
      assert(pretty.contains("HUE_PSK"), pretty)
      assert(pretty.contains("32 hex digits"), pretty)
      assertNoLeak(pretty, raw)
    }

    test(s"a key with $name raises a ConfigReaderException naming HUE_PSK without the value") {
      AppConfig.load[IO](source(raw)).attempt.map {
        case Left(e: ConfigReaderException[?]) =>
          val message = e.getMessage
          assert(message.contains("HUE_PSK"), message)
          assert(message.contains("32 hex digits"), message)
          assertNoLeak(message, raw)
        case other => fail(s"unexpected $other")
      }
    }
  }

  test("a valid key loads through AppConfig.load") {
    AppConfig.load[IO](source(key)).map(c => assertEquals(c.hue.creds.psk.bytes, ByteVector.fromValidHex(key)))
  }

  private def assertNoLeak(error: String, raw: String): Unit = {
    if (raw.nonEmpty) assert(!error.contains(raw), error)
    raw.sliding(4).foreach(w => assert(!error.contains(w), s"error contains a 4-character part of the input: $error"))
  }
}
