import cats.effect.*
import cats.effect.{ExitCode, IOApp}
import cats.effect.kernel.Resource
import cats.effect.std.Env
import client.{DtlsEmitter, EntertainmentStream, HttpClients, HueClient, SpotifyClient, TokenFile, TokenStore}
import cats.syntax.all.*
import beat.{BeatTracking, TrackerSettings}
import domain.config.{AppConfig, BeatSource}
import domain.hue.{MusicArea, Zone}
import domain.engine.RGB
import settings.{LiveSettings, SettingsRoutes, SettingsServer, ShowControls}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pureconfig.ConfigSource
import pureconfig.error.ConfigReaderException
object Main extends IOApp {
  private val logger = Slf4jLogger.getLogger[IO]

  private def controls(engine: SyncEngine[IO], lights: Lights[IO]): ShowControls[IO] =
    new ShowControls[IO] {
      def applyNow: IO[Unit] = engine.applyNow
      def areas: IO[List[MusicArea]] = lights.areas
      def palette: IO[Vector[RGB]] = engine.palette.map(_.fold(Vector.empty)(_.colors))
    }

  override def run(args: List[String]): IO[ExitCode] = start(ConfigSource.default)

  def start(source: ConfigSource): IO[ExitCode] = {
    val resources = for {
      config <- Resource.eval(AppConfig.load[IO](source))
      clients <- HttpClients.resource[IO]

      env <- Resource.eval(Env[IO].entries.map(_.toMap))
      store <- Resource.eval(TokenStore.at[IO](TokenFile.locate(config.spotify.tokenFile, env)))
      spotify <- SpotifyClient.resource[IO](clients.spotify, config.spotify, store)
      hueClient = new HueClient[IO](config.hue.creds, clients.bridge)
      emitter = new DtlsEmitter[IO](config.hue.creds)

      lights <- Resource.eval(Lights.fromBridge[IO](hueClient))
      areas <- Resource.eval(lights.areas)
      area <- Resource.eval(MusicArea.choose(areas, config.lightShow.area, config.hue.zoneId).leftMap(new IllegalArgumentException(_)).liftTo[IO])
      _ <- Resource.eval(logger.info(
          s"Entertainment areas on the bridge: ${areas.map(a => s"${a.name} (${a.lights} lights)").mkString(", ")}. Playing on ${area.name}."
        ))
      live <- Resource.eval(LiveSettings.make[IO](config.lightShow.copy(area = Some(area.name)), areas))
      channels <- Resource.eval(hueClient.getChannels(area.id))
      audio = config.lightShow.beatSource match {
        case BeatSource.Audio => BeatTracking.listen[IO](config.lightShow.audio.device, live.applied.map(TrackerSettings.from))
        case BeatSource.Spotify => fs2.Stream.empty
      }
      _ <- Resource.eval(logger.info(
          s"Listening for the beat on '${config.lightShow.audio.device}'. If it never locks on, check that music is playing through it and that your terminal has microphone access."
        ).whenA(config.lightShow.beatSource == BeatSource.Audio))
      engine <- Resource.eval(SyncEngine.make[IO](config, live, spotify, Zone(area.id, channels), lights, audio = audio))
      _ <-
        if (!config.ui.enabled) Resource.unit[IO]
        else
          SettingsServer.resource[IO](config.ui, SettingsRoutes[IO](live, controls(engine, lights)))
            .evalMap(server => logger.info(s"Settings page at ${server.baseUri}; changes take effect when the next song starts"))
    } yield (hueClient, emitter, engine)

    resources.use { (hueClient, emitter, engine) =>
      engine.run.through(EntertainmentStream.toBridge(hueClient, emitter)).compile.drain.as(ExitCode.Success)
    }.recoverWith { case e: ConfigReaderException[?] =>
      logger.error(e.failures.prettyPrint()).as(ExitCode.Error)
    }
  }
}
