package client

import cats.Applicative
import cats.effect.Deferred
import cats.syntax.all.*

/** Carries the code from the callback server to the browser sign-in waiting for it, one code per attempt. */
trait SignInCodes[F[_]] {

  /** Opens an attempt, or joins the one still waiting, and gives the wait for its code. A spent attempt is never reused. */
  def attempt: F[F[String]]

  /** Hands over a code from the callback: false if no attempt was waiting for one. */
  def hand(code: String): F[Boolean]
}

object SignInCodes {

  /** A single attempt that `deferred` completes. */
  def once[F[_]: Applicative](deferred: Deferred[F, String]): SignInCodes[F] =
    new SignInCodes[F] {
      def attempt: F[F[String]] = deferred.get.pure[F]
      def hand(code: String): F[Boolean] = deferred.complete(code)
    }
}
