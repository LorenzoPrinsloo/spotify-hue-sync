package audio

import audio.AudioInput.{choose, format, toMono}
import beat.AudioFrame
import cats.effect.{Async, Clock, Resource}
import cats.syntax.all.*
import fs2.Stream
import javax.sound.sampled.{AudioSystem, DataLine, TargetDataLine}

/** Captures audio from an input device, such as BlackHole, as mono frames of `hop` samples. */
object AudioCapture {

  private val lineInfo = new DataLine.Info(classOf[TargetDataLine], format)

  /** Names of the inputs that can capture in `format`. */
  def inputNames: List[String] =
    AudioSystem.getMixerInfo.toList.filter(info => AudioSystem.getMixer(info).isLineSupported(lineInfo)).map(_.getName)

  private def open[F[_]: Async](device: String, hop: Int): Resource[F, TargetDataLine] =
    Resource.make(
      Async[F].blocking(choose(device, inputNames).map(name => (name, inputNames))).flatMap {
        case None => Async[F].blocking(inputNames).flatMap(names => Async[F].raiseError(AudioDeviceNotFound(device, names)))
        case Some((name, names)) =>
          Async[F].blocking(AudioSystem.getMixerInfo.find(_.getName == name)).flatMap(
            _.fold(Async[F].raiseError(AudioDeviceNotFound(device, names))) { mixer =>
              Async[F].blocking {
                val line = AudioSystem.getMixer(mixer).getLine(lineInfo).asInstanceOf[TargetDataLine]
                line.open(format, hop * format.getFrameSize * 16)
                line.start()
                line
              }
            }
          )
      }
    )(line => Async[F].blocking { line.stop(); line.close() })

  /** Reads within this long of each other anchor the capture clock: long enough to catch a prompt read. */
  private val anchorSeconds = 10

  def frames[F[_]: Async](device: String, hop: Int): Stream[F, AudioFrame] =
    Stream.resource(open[F](device, hop)).flatMap { line =>
      Stream
        .repeatEval {
          for {
            samples <- Async[F].blocking {
              val bytes = new Array[Byte](hop * format.getFrameSize)
              val read = line.read(bytes, 0, bytes.length)
              toMono(bytes, read)
            }
            readAt <- Clock[F].monotonic
          } yield (samples, readAt)
        }
        .mapAccumulate(CaptureClock(format.getSampleRate.toDouble, (anchorSeconds * format.getSampleRate / hop).toInt)) {
          case (clock, (samples, readAt)) =>
            val (next, capturedAt) = clock.next(samples.length, readAt)
            (next, AudioFrame(samples, capturedAt))
        }
        .map(_._2)
    }
}
