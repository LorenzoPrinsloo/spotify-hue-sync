package testkit

import cats.effect.{IO, Ref, Resource}
import cats.syntax.functor.*
import ch.qos.logback.classic.{Level, Logger, spi}
import ch.qos.logback.core.read.ListAppender
import com.comcast.ip4s.*
import fs2.io.net.Network
import fs2.io.net.tls.TLSContext
import org.http4s.*
import org.http4s.client.Client
import org.http4s.ember.server.EmberServerBuilder
import org.slf4j.LoggerFactory

import javax.net.ssl.SSLException

object Tls {

  private val password = "changeit"

  def selfSigned: IO[TLSContext[IO]] =
    Network[IO].tlsContext.fromKeyStoreResource("self-signed.p12", password.toCharArray, password.toCharArray)

  def server(app: HttpApp[IO]): Resource[IO, (Int, IO[List[Request[IO]]])] =
    for {
      tls <- Resource.eval(selfSigned)
      seen <- Resource.eval(Ref.of[IO, List[Request[IO]]](Nil))
      recording = HttpApp[IO](req => seen.update(req :: _) *> app.run(req))
      server <- EmberServerBuilder.default[IO].withTLS(tls).withHost(ipv4"127.0.0.1").withPort(port"0").withHttpApp(recording).build
    } yield (server.address.getPort, seen.get.map(_.reverse))

  def redirectTo(port: Int)(client: Client[IO]): Client[IO] =
    Client[IO] { req =>
      val authority = Uri.Authority(host = Uri.RegName("localhost"), port = Some(port))
      client.run(req.withUri(req.uri.copy(authority = Some(authority))))
    }

  def causes(e: Throwable): List[Throwable] =
    Iterator.unfold[Throwable, Option[Throwable]](Some(e))(_.map(t => (t, Option(t.getCause)))).take(20).toList

  def isTlsFailure(e: Throwable): Boolean = causes(e).exists {
    case _: SSLException => true
    case _ => false
  }

  def logged(appender: ListAppender[spi.ILoggingEvent]): List[spi.ILoggingEvent] =
    appender.synchronized(List.from(appender.list.toArray(Array.empty[spi.ILoggingEvent])))

  def loggingAt(name: String, level: Level): Resource[IO, Unit] =
    Resource
      .make(IO {
        val logger = LoggerFactory.getLogger(name).asInstanceOf[Logger]
        val previous = logger.getLevel
        logger.setLevel(level)
        previous
      })(previous => IO(LoggerFactory.getLogger(name).asInstanceOf[Logger].setLevel(previous)))
      .void

  def capturedLogs: Resource[IO, ListAppender[spi.ILoggingEvent]] =
    Resource.make(IO {
      val appender = new ListAppender[spi.ILoggingEvent] {
        override def append(e: spi.ILoggingEvent): Unit = {
          e.prepareForDeferredProcessing()
          super.append(e)
        }
      }
      appender.start()
      LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger].addAppender(appender)
      appender
    })(appender => IO(LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger].detachAppender(appender)))
}
