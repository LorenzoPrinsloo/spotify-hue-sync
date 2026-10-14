package domain.engine

import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import scodec.Codec

class RGBSpec extends ScalaCheckSuite {

  given Arbitrary[RGB] = Arbitrary(
    for {
      r <- Gen.choose(0, 255)
      g <- Gen.choose(0, 255)
      b <- Gen.choose(0, 255)
    } yield RGB.safe(r, g, b).get
  )

  property("safe accepts a colour only when every channel is within 0-255") {
    forAll { (r: Int, g: Int, b: Int) =>
      val inRange = Seq(r, g, b).forall(v => v >= 0 && v <= 255)
      RGB.safe(r, g, b).isDefined == inRange
    }
  }

  property("the complement of a colour's complement is the colour itself") {
    forAll { (c: RGB) =>
      c.compliment.flatMap(_.compliment) == Some(c)
    }
  }

  property("a colour and its complement add up to white on every channel") {
    forAll { (c: RGB) =>
      c.compliment.exists(o => c.toVector.zip(o.toVector).forall(_ + _ == 255))
    }
  }

  property("analogous returns three colours with the original in the middle") {
    forAll { (c: RGB) =>
      val colours = c.analogous
      colours.size == 3 && colours(1) == c
    }
  }

  property("the wire codec round-trips every colour") {
    forAll { (c: RGB) =>
      val codec = summon[Codec[RGB]]
      codec.encode(c).flatMap(codec.decodeValue).toOption == Some(c)
    }
  }
}
