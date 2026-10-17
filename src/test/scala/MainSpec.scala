import ch.qos.logback.classic.{Logger, spi}
import ch.qos.logback.core.read.ListAppender
import cats.effect.{ExitCode, IO, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.net.Network
import munit.CatsEffectSuite
import org.slf4j.LoggerFactory
import pureconfig.ConfigSource

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class MainSpec extends CatsEffectSuite {

  private val digits = "12345678901234567890123456789012"

  private val badKeys: List[(String, String)] = List(
    "a non-hex digit" -> (digits.take(31) + "g"),
    "an empty key" -> "",
    "31 digits" -> digits.take(31),
    "33 digits" -> (digits + "3"),
    "a 0x prefix" -> ("0x" + digits),
    "a quote inside" -> (digits.take(16) + "\"" + digits.drop(16)),
    "a space inside" -> (digits.take(16) + " " + digits.drop(16))
  )

  private def hocon(psk: String, port: Int): String = {
    val escaped = psk.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    s"""spotify { client-id = "id", client-secret = "secret", refresh-token = "refresh" }
       |hue { creds { address = "127.0.0.1:$port", username = "user", psk = "$escaped" } }
       |""".stripMargin
  }

  private def source(psk: String, port: Int): ConfigSource =
    ConfigSource.string(hocon(psk, port)).withFallback(ConfigSource.resources("reference.conf"))

  private def bridgeListener: Resource[IO, (Int, IO[Int])] =
    for {
      accepted <- Resource.eval(Ref.of[IO, Int](0))
      (address, sockets) <- Network[IO].serverResource(Some(ip"127.0.0.1"), None)
      _ <- sockets
        .evalMap(socket => accepted.update(_ + 1) *> socket.endOfOutput *> socket.endOfInput)
        .compile
        .drain
        .background
    } yield (address.port.value, accepted.get)

  private def capturedLogs: Resource[IO, ListAppender[spi.ILoggingEvent]] =
    Resource.make(IO {
      val appender = new ListAppender[spi.ILoggingEvent]
      appender.start()
      LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger].addAppender(appender)
      appender
    })(appender => IO(LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger].detachAppender(appender)))

  List("an upper-case key" -> digits.replace('1', 'A'), "a key with a trailing newline" -> (digits + "\n")).foreach { (name, psk) =>
    test(s"start gets past config validation and connects to the bridge with $name") {
      bridgeListener.use { (port, connections) =>
        Main.start(source(psk, port)).timeout(5.seconds).attempt *> connections.map(count => assert(count >= 1, s"connections: $count"))
      }
    }
  }

  badKeys.foreach { (name, psk) =>
    test(s"start exits with an error and never contacts the bridge for $name") {
      (bridgeListener, capturedLogs).tupled.use { case ((port, connections), appender) =>
        Main.start(source(psk, port)).timeout(20.seconds).flatMap { exit =>
          connections.map { count =>
            val messages = appender.list.asScala.toList.map(_.getFormattedMessage)
            val windows = psk.sliding(4).filter(_.length == 4).toList
            assertEquals(exit, ExitCode.Error)
            assertEquals(count, 0)
            val errors = appender.list.asScala.toList.filter(_.getLevel == ch.qos.logback.classic.Level.ERROR).map(_.getFormattedMessage)
            assert(errors.exists(m => m.contains("HUE_PSK") && m.contains("32 hex digits")), errors.toString)
            assert(!messages.exists(m => windows.exists(m.contains)), "a log message contains part of the key")
          }
        }
      }
    }
  }
}
