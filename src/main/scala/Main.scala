import cats.effect.*
import cats.effect.{ExitCode, IOApp}
import cats.effect.kernel.Resource
import fs2.Stream
import client.{CachedSpotifyClient, DtlsEmitter, HueClient}
import cats.implicits.*
import beat.BeatTracking
import domain.config.{AppConfig, BeatSource}
import domain.hue.Zone
import fs2.io.net.tls.TLSContext
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.client.middleware.FollowRedirect
object Main extends IOApp {
  private val logger = Slf4jLogger.getLogger[IO]
  
  override def run(args: List[String]): IO[ExitCode] = {
    val resources = for {
      config <- Resource.eval(AppConfig.load[IO])
      tlsContext <- Resource.eval(TLSContext.Builder.forAsync[IO].insecure)
      httpClient <- EmberClientBuilder.default[IO].withTLSContext(tlsContext).build.map {c => FollowRedirect(2)(c)}

      spotify <- CachedSpotifyClient.resource[IO](httpClient, config.spotify)
      hueClient = new HueClient[IO](config.hue.creds, httpClient)
      emitter = new DtlsEmitter[IO](config.hue.creds)

      channels <- Resource.eval(hueClient.getChannels(config.hue.zoneId))
      audio = config.lightShow.beatSource match {
        case BeatSource.Audio   => BeatTracking.listen[IO](config.lightShow.audio, config.lightShow.reactive)
        case BeatSource.Spotify => fs2.Stream.empty
      }
      _ <- Resource.eval(logger.info(s"Listening for the beat on '${config.lightShow.audio.device}'. If it never locks on, check that music is playing through it and that your terminal has microphone access.").whenA(config.lightShow.beatSource == BeatSource.Audio))
      engine <- Resource.eval(SyncEngine.make[IO](config, spotify, Zone(config.hue.zoneId, channels), audio = audio))
    } yield (config, hueClient, emitter, engine)

    resources.use { case (config, hueClient, emitter, engine) =>
      for {
        _ <- hueClient.setStreaming(config.hue.zoneId, active = true)
        _ <- Stream.resource(emitter.resource).flatMap { connector =>
            engine.run.evalMap(frame => emitter.send(connector, frame))
          }
          .onFinalize(hueClient.setStreaming(config.hue.zoneId, active = false))
          .compile
          .drain

      } yield ExitCode.Success
    }
  }
}
