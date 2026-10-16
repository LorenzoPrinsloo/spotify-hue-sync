package client

import cats.effect.*
import cats.syntax.all.*
import domain.hue.{Frame, HueCredentials}
import org.eclipse.californium.scandium.DTLSConnector
import org.eclipse.californium.scandium.config.DtlsConnectorConfig
import org.eclipse.californium.scandium.dtls.pskstore.AdvancedSinglePskStore
import org.eclipse.californium.elements.RawData
import org.eclipse.californium.elements.AddressEndpointContext
import org.eclipse.californium.elements.config.Configuration
import java.net.InetSocketAddress
import scodec.Codec
import scodec.bits.ByteVector
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.eclipse.californium.scandium.config.DtlsConfig
import org.eclipse.californium.elements.config.UdpConfig

class DtlsEmitter[F[_]: Async](creds: HueCredentials):

  private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]
  private lazy val pskBytes = ByteVector.fromHex(creds.psk).getOrElse(ByteVector.empty).toArray
  private lazy val pskStore = new AdvancedSinglePskStore(creds.username, pskBytes)
  private val destination = new InetSocketAddress(creds.address, 2100)

  /** A new connector, so each entertainment session starts with a fresh handshake. */
  private def newConnector: DTLSConnector = {
    DtlsConfig.register()
    UdpConfig.register()
    val builder = DtlsConnectorConfig.builder(Configuration.getStandard())
      .setAddress(new InetSocketAddress(0))
      .setAdvancedPskStore(pskStore)
    new DTLSConnector(builder.build())
  }

  def resource: Resource[F, DTLSConnector] =
    Resource.make(
      Async[F].delay {
        val connector = newConnector
        connector.start()
        connector
      } <* logger.info("DTLS Connector started")
    )(c => Async[F].delay(c.destroy()) <* logger.info("DTLS Connector stopped"))

  def send(connector: DTLSConnector, frame: Frame): F[Unit] =
    Codec[Frame].encode(frame).toEither match {
      case Right(bits) =>
        logger.debug(s"RAW PACKET HEX: ${bits.toHex}")
          .flatTap { _ =>
            Async[F].blocking {
              val context = new AddressEndpointContext(destination)
              val data = RawData.outbound(bits.toByteArray, context, null, false)
              connector.send(data)
            }
          }
      case Left(err) =>
        logger.error(s"Failed to encode Hue Frame: $err")
    }
