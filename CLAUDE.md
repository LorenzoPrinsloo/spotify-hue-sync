# Working on spotify-hue-sync

How the app works is in `docs/how-it-works.md`, and how the code is laid out is in `docs/architecture.md`.

## Code

- Scala 3. New code uses brace syntax, not colon-indented blocks, and `given`/`using`/`summon`, not `implicit`.
- Typelevel stack: Cats Effect (`F[_]: Async`, `Resource`, `Ref`), fs2, http4s Ember, circe, pureconfig. No `Future`, no threads, no `unsafeRun*` outside `Main`.
- Failures are typed: errors raised in `F`, `Either` or a list of problems for validation. Config is checked at startup (`LightShowConfig.problems`) and the app refuses to start on a bad value.
- Keep the colour, pulse and beat code pure. Time, randomness and I/O come in from outside.
- Make the smallest change at the point of the problem. Don't reshape working code alongside a fix.
- Comment only what the code can't say.

## Tests

- munit with ScalaCheck properties, `CatsEffectSuite`, and `TestControl` for anything timed. Spotify and Hue are faked over HTTP.
- No comments explaining a test. The test's name says what it checks.
- The settings page (`ui/`, Scala.js and Laminar) is tested in Node.js.

## Before a change is done

- `sbt fix` applies the Scalafix rewrites and scalafmt formatting.
- `sbt check` is what CI runs. It lints, then tests with coverage, and fails below 90% of statements or 85% of branches overall, or 70% of statements in any file.
- Don't lower the minimums, widen the coverage exclusions, or turn off a Scalafix rule to get a change through.

## Secrets

Credentials live in `.env`. Never print, log or commit it, or anything from it.
