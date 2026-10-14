package audio

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class AudioInputSpec extends ScalaCheckSuite {

  private def stereo(frames: Seq[(Short, Short)]): Array[Byte] =
    frames.flatMap { (l, r) => Seq(l & 0xff, (l >> 8) & 0xff, r & 0xff, (r >> 8) & 0xff).map(_.toByte) }.toArray

  test("mixes 16-bit little-endian stereo down to mono samples") {
    val bytes = stereo(Seq((16384.toShort, 16384.toShort), ((-32768).toShort, 0.toShort), (0.toShort, 32767.toShort)))
    val mono = AudioInput.toMono(bytes, bytes.length)
    assertEquals(mono.length, 3)
    assertEqualsFloat(mono(0), 0.5f, 1e-6f)
    assertEqualsFloat(mono(1), -0.5f, 1e-6f)
    assertEqualsFloat(mono(2), 32767f / 65536f, 1e-6f)
  }

  property("mono samples always stay within -1 and 1") {
    forAll(Gen.listOf(Gen.zip(Gen.choose(Short.MinValue, Short.MaxValue), Gen.choose(Short.MinValue, Short.MaxValue)))) { frames =>
      val bytes = stereo(frames)
      val mono = AudioInput.toMono(bytes, bytes.length)
      mono.length == frames.size && mono.forall(s => s >= -1f && s <= 1f)
    }
  }

  test("only converts the bytes that were actually read") {
    val bytes = stereo(Seq.fill(10)((1000.toShort, 1000.toShort)))
    assertEquals(AudioInput.toMono(bytes, 8).length, 2)
  }

  test("picks the input whose name contains the configured device, ignoring case") {
    val inputs = List("MacBook Pro Microphone", "BlackHole 2ch", "ZoomAudioDevice")
    assertEquals(AudioInput.choose("blackhole", inputs), Some("BlackHole 2ch"))
    assertEquals(AudioInput.choose(" BlackHole 2ch ", inputs), Some("BlackHole 2ch"))
    assertEquals(AudioInput.choose("Loopback", inputs), None)
  }

  test("a missing device lists the inputs that are available") {
    val error = AudioDeviceNotFound("BlackHole", List("MacBook Pro Microphone"))
    assert(error.getMessage.contains("BlackHole") && error.getMessage.contains("MacBook Pro Microphone"))
  }
}
