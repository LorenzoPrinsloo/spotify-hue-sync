import cats.effect.{Concurrent, Ref}
import cats.syntax.all.*
import client.HueClient
import domain.hue.{MusicArea, Zone}

/** The lights the show can play on: the bridge's entertainment areas, and the channels in each. */
trait Lights[F[_]] {

  /** The areas on the bridge now. */
  def areas: F[List[MusicArea]]

  /** The zone of the area with this name, or None if there's no such area. */
  def zone(area: String): F[Option[Zone]]
}

object Lights {

  /** The bridge's areas, with each area's channels read once and then remembered. */
  def fromBridge[F[_]: Concurrent](hue: HueClient[F]): F[Lights[F]] =
    Ref.of[F, Map[String, Zone]](Map.empty).map { known =>
      new Lights[F] {
        def areas: F[List[MusicArea]] = hue.areas

        def zone(area: String): F[Option[Zone]] =
          known.get.map(_.get(area.toLowerCase)).flatMap {
            case Some(zone) => Concurrent[F].pure(Some(zone))
            case None =>
              hue.areas.map(_.find(_.name.equalsIgnoreCase(area))).flatMap(_.traverse { found =>
                hue.getChannels(found.id).map(Zone(found.id, _)).flatTap(zone => known.update(_.updated(area.toLowerCase, zone)))
              })
          }
      }
    }
}
