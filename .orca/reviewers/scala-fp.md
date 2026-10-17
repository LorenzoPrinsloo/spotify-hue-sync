---
description: Reviews Scala code for this project's functional idioms on the Typelevel stack — effects in F, Resource and fiber scope, Ref over shared mutable state, typed failures, immutable ADTs, enums for closed sets, Option over empty strings and sentinels, refined values, Scala 3 brace syntax and givens.
files: \.(scala|sbt)$
---

## Scope

Review only the functional idioms below; other dimensions belong to other reviewers. This project is built on the
Typelevel stack (Cats Effect, fs2, http4s, circe), not direct style. Ignore any guidance about Ox, `supervised` or
braceless syntax.

## Aspects

- **Effects stay in `F`**: I/O, time, randomness and logging go through `F[_]: Async` (or `IO` in `Main`) and are
  passed in, never called inside pure logic. The colour, pulse, palette and beat-analysis code is pure and
  property-tested; flag anything that makes it effectful or reads the clock.
- **No shared mutable state**: shared state is a `Ref`, `Deferred`, bounded `Queue` or `Hotswap`, never a `var`
  field, an `AtomicReference` or `synchronized`. A local `var` inside a tight numeric loop is fine.
- **Resource and fiber scope**: anything acquired is a `Resource`. Background work is tied to a scope (`Resource`,
  `background`, `Supervisor`, fs2 `concurrently`), never a bare `.start` with no join or cancel. No `unsafeRun*`
  outside `Main`.
- **Typed failures**: errors are case classes or enums raised with `raiseError`, recovered by type, never by matching
  `getMessage`. Validation collects problems (`Either` or a list, as `LightShowConfig.problems` does) and config is
  checked at startup. `Option` is never an error.
- **Absence is `Option`**: a value that may be absent is an `Option`, never `null`, an empty string or another
  sentinel. That includes optional config read from the environment: an unset or blank variable is `None`, not `""`.
- **Immutable data**: case classes, enums and immutable collections. Different states are different
  types, not `Option` or `Boolean` fields whose combinations include impossible states. Constrained values use
  refined types or a smart constructor returning `Either`. No boolean blindness at call sites.
- **Closed sets are enums**: a closed set of cases is a Scala 3 `enum`, whose cases may carry fields, not a sealed
  abstract class or a sealed-trait hierarchy. Use those only where an enum genuinely can't express it.
- **fs2**: streams are values. Prefer `evalMap`, `parEvalMap`, `merge`, `zip` and `metered` to hand-wired queues and
  fibers. Queues are bounded.
- **Scala 3**: new code uses brace syntax, `given`/`using`/`summon`, extension methods and `derives`. No `implicit`,
  `implicitly` or other Scala 2 leftovers. Public defs and givens have explicit types.
- **One concern per function**: a function doing several steps reads as a sequence of named steps. Flag long bodies
  where named extracts would make the intent readable.
