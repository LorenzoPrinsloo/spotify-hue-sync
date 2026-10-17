import cats.effect.{ExitCode, IO, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.net.Network
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.dsl.io.*
import pureconfig.ConfigSource

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import testkit.Tls

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

  List("an upper-case key" -> digits.replace('1', 'A'), "a key with a trailing newline" -> (digits + "\n")).foreach { (name, psk) =>
    test(s"start gets past config validation and connects to the bridge with $name") {
      bridgeListener.use { (port, connections) =>
        Main.start(source(psk, port)).timeout(5.seconds).attempt *> connections.map(count => assert(count >= 1, s"connections: $count"))
      }
    }
  }

  private val configurations =
    """{ "data": [ { "id": "up", "metadata": { "name": "Upstairs" }, "configuration_type": "music",
      |  "channels": [ { "channel_id": 0, "members": [ { "service": { "rid": "e-1" } } ] },
      |                { "channel_id": 1, "members": [ { "service": { "rid": "e-1" } } ] } ] } ] }""".stripMargin
  private val services = """{ "data": [ { "id": "e-1", "owner": { "rid": "d-1" } } ] }"""
  private val rooms = """{ "data": [ { "metadata": { "name": "Hall" }, "children": [ { "rid": "d-1" } ] } ] }"""
  private val zone =
    """{ "errors": [], "data": [ { "id": "up", "type": "entertainment_configuration", "channels": [ { "channel_id": 0, "members": [] }, { "channel_id": 1, "members": [] } ] } ] }"""

  private def selfSignedBridge(actions: Ref[IO, List[String]]): Resource[IO, (Int, IO[List[Request[IO]]])] =
    Tls.server(HttpRoutes.of[IO] {
      case GET -> Root / "clip" / "v2" / "resource" / "entertainment_configuration" => Ok(configurations)
      case GET -> Root / "clip" / "v2" / "resource" / "entertainment" => Ok(services)
      case GET -> Root / "clip" / "v2" / "resource" / "room" => Ok(rooms)
      case GET -> Root / "clip" / "v2" / "resource" / "entertainment_configuration" / "up" => Ok(zone)
      case req @ PUT -> Root / "clip" / "v2" / "resource" / "entertainment_configuration" / "up" =>
        req.bodyText.compile.string.flatMap { text =>
          val action = io.circe.parser.parse(text).flatMap(_.hcursor.downField("action").as[String]).getOrElse("?")
          actions.update(action :: _) *> Ok("""{ "errors": [], "data": [] }""")
        }
    }.orNotFound)

  test("start talks to a bridge with a self-signed certificate") {
    (Ref.of[IO, List[String]](Nil).toResource, Tls.capturedLogs).tupled.use { (actions, appender) =>
      selfSignedBridge(actions).use { (port, requests) =>
        val config = ConfigSource
          .string(hocon(digits, port) + "ui.enabled = false\npolling-interval-ms = 3600000\n")
          .withFallback(ConfigSource.resources("reference.conf"))
        Main.start(config).timeout(5.seconds).attempt *> (requests, actions.get).mapN { (seen, put) =>
          val calls = seen.map(r => r.method.name -> r.uri.path.renderString)
          val base = "/clip/v2/resource"
          List("entertainment_configuration", "entertainment", "room", "entertainment_configuration/up").foreach { path =>
            assert(calls.contains("GET" -> s"$base/$path"), calls.toString)
          }
          assert(put.contains("start"), put.toString)
          val certificateErrors = Tls.logged(appender)
            .filter(_.getLevel.isGreaterOrEqual(ch.qos.logback.classic.Level.WARN))
            .map(_.getFormattedMessage)
            .filterNot(_.contains("SpotifyTlsFailure"))
            .filter(m => List("SSLHandshakeException", "PKIX").exists(m.contains))
          assertEquals(certificateErrors, Nil)
        }
      }
    }
  }

  badKeys.foreach { (name, psk) =>
    test(s"start exits with an error and never contacts the bridge for $name") {
      (bridgeListener, Tls.capturedLogs).tupled.use { case ((port, connections), appender) =>
        Main.start(source(psk, port)).timeout(20.seconds).flatMap { exit =>
          connections.map { count =>
            val messages = Tls.logged(appender).map(_.getFormattedMessage)
            val windows = psk.sliding(4).filter(_.length == 4).toList
            assertEquals(exit, ExitCode.Error)
            assertEquals(count, 0)
            val errors = Tls.logged(appender).filter(_.getLevel == ch.qos.logback.classic.Level.ERROR).map(_.getFormattedMessage)
            assert(errors.exists(m => m.contains("HUE_PSK") && m.contains("32 hex digits")), errors.toString)
            assert(!messages.exists(m => windows.exists(m.contains)), "a log message contains part of the key")
          }
        }
      }
    }
  }
}
