package domain.hue

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll
import scodec.bits.ByteVector

class PskSpec extends ScalaCheckSuite {

  private val hexDigit = Gen.oneOf("0123456789abcdefABCDEF")
  private val hex32 = Gen.listOfN(32, hexDigit).map(_.mkString)
  private val hexN = (n: Int) => Gen.listOfN(n, hexDigit).map(_.mkString)

  property("32 hex digits in any case parse to the bytes they spell") {
    forAll(hex32) { s =>
      Psk.parse(s).map(_.bytes) == Right(ByteVector.fromValidHex(s))
    }
  }

  property("surrounding whitespace is ignored") {
    forAll(hex32, Gen.stringOf(Gen.oneOf(' ', '\n', '\t')), Gen.stringOf(Gen.oneOf(' ', '\n', '\t'))) { (s, before, after) =>
      Psk.parse(before + s + after) == Psk.parse(s)
    }
  }

  property("fewer or more than 32 digits are rejected") {
    forAll(Gen.oneOf(Gen.choose(0, 31), Gen.choose(33, 64)).flatMap(hexN)) { s =>
      Psk.parse(s).isLeft
    }
  }

  property("a 0x prefix is rejected") {
    forAll(hex32) { s =>
      Psk.parse("0x" + s.drop(2)).isLeft && Psk.parse("0x" + s).isLeft
    }
  }

  property("a non-hex character, quote or inner whitespace in place of a digit is rejected") {
    forAll(hex32, Gen.choose(1, 30), Gen.oneOf("g", " ", "_", "\"", "\n")) { (s, at, bad) =>
      Psk.parse(s.take(at) + bad + s.drop(at + 1)).isLeft
    }
  }

  property("the rejection names HUE_PSK and never echoes the input") {
    forAll(hexN(31)) { s =>
      Psk.parse(s).fold(m => m.contains("HUE_PSK") && m.contains("32 hex digits") && !m.contains(s), _ => false)
    }
  }

  property("toString does not reveal the key") {
    forAll(hex32) { s =>
      val shown = Psk.parse(s).map(_.toString).getOrElse("")
      shown == "Psk(<redacted>)"
    }
  }
}
