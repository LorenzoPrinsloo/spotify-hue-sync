package audio

import javax.sound.sampled.AudioFormat

case class AudioDeviceNotFound(device: String, available: List[String])
    extends Exception(s"No audio input matching '$device'. Available inputs: ${available.mkString(", ")}")

/** The audio the app reads from an input device: its format, choosing the device, and turning it into mono samples. */
object AudioInput {

  val format: AudioFormat = new AudioFormat(44100f, 16, 2, true, false)

  /** The first input whose name contains `device`, ignoring case. */
  def choose(device: String, inputs: List[String]): Option[String] =
    inputs.find(_.toLowerCase.contains(device.trim.toLowerCase))

  /** 16-bit little-endian stereo bytes to mono samples in [-1, 1]. */
  def toMono(bytes: Array[Byte], length: Int): Array[Float] =
    Array.tabulate(length / 4) { i =>
      val left = ((bytes(4 * i + 1) << 8) | (bytes(4 * i) & 0xff)).toShort
      val right = ((bytes(4 * i + 3) << 8) | (bytes(4 * i + 2) & 0xff)).toShort
      (left + right) / 65536f
    }
}
