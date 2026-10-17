package client

import cats.effect.{Deferred, IO, Ref}
import cats.effect.unsafe.implicits.global
import domain.spotify.{SpotifyCredentials, SpotifySession}
import munit.ScalaCheckSuite
import org.http4s.{HttpRoutes, Response, Status}
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class SpotifyRenewSpec extends ScalaCheckSuite {

  private val tokenGen = Gen.identifier.suchThat(_.length >= 14).map("rt-" + _)
  private val statusGen = Gen.oneOf(Status.BadRequest, Status.Unauthorized, Status.Forbidden, Status.InternalServerError, Status.BadGateway)
  private val bodyGen = Gen.oneOf("""{"error":"invalid_grant"}""", """{"error":"invalid_client"}""")

  private def renewAgainst(token: String, fake: HttpRoutes[IO]): Either[Throwable, String] =
    (for {
      ref <- Ref.of[IO, Option[SpotifySession]](Some(SpotifySession("expired", token)))
      deferred <- Deferred[IO, String]
      spotify = new SpotifyClient[IO](Client.fromHttpApp(fake.orNotFound), SpotifyCredentials("id", "secret", "unused"), ref, deferred)
      outcome <- spotify.renew.attempt
    } yield outcome).unsafeRunSync()

  property("a failed renewal is a SpotifyTokenError named Renew, Rejected only for 400 invalid_grant") {
    forAll(tokenGen, statusGen, bodyGen) { (token, status, body) =>
      val fake = HttpRoutes.of[IO] { case POST -> Root / "api" / "token" => IO.pure(Response[IO](status).withEntity(body)) }
      val expectRejected = status == Status.BadRequest && body.contains("invalid_grant")
      renewAgainst(token, fake) match {
        case Left(SpotifyTokenError.Rejected("Renew", _, _)) => expectRejected
        case Left(SpotifyTokenError.Failed("Renew", _, _)) => !expectRejected
        case _ => false
      }
    }
  }

  property("a renewal failure message never contains the refresh token sent") {
    forAll(tokenGen, statusGen, bodyGen) { (token, status, body) =>
      val fake = HttpRoutes.of[IO] { case POST -> Root / "api" / "token" => IO.pure(Response[IO](status).withEntity(body)) }
      renewAgainst(token, fake) match {
        case Left(e: SpotifyTokenError) => !e.getMessage.contains(token)
        case _ => false
      }
    }
  }
}
