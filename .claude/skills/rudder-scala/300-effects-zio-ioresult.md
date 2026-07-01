# 300 — Effects: ZIO & `IOResult`

We use **ZIO** as our effect handler — but only for *effects*, never for DI or service
wiring (that is constructor-based, see [`102`](102-traits-and-dependency-injection.md)).

## The two result types

Defined in `com.normation.errors` (`utils/.../ZioCommons.scala`):

```scala
type PureResult[A] = Either[RudderError, A]      // pure, may fail, fully evaluated
type IOResult[A]   = ZIO[Any, RudderError, A]    // effectful, may fail, must be run
```

- The environment `R` is **always `Any`** — we do not use ZIO's environment for DI.
- The error channel `E` is **always `RudderError`** (see [`301`](301-error-model.md)).
- `IOResult` is the one and only name for this type — always use it.

### When to use which — prefer the least powerful

**Always reach for the least powerful abstraction that does the job.** `IOResult` can do
*anything* (I/O, mutation, concurrency, fail in any way) — that power is exactly why it
is the *wrong* default: a `PureResult` signature *promises* the caller there are no side
effects, is trivially testable, and composes without a runtime. Don't reach for
`IOResult` just because it's available.

- **Plain value / `Option`** — for total or near-total pure helpers (no failure to
  report). Most code.
- **`PureResult[A]`** — pure computation that can fail (parsing, validation,
  business-rule checks) with **no** side effect. Prefer this whenever the logic is pure,
  even if its caller is effectful — keep the pure core pure and lift only at the call
  site (see [`101`](101-architecture-ddd-hexagonal.md), [`302`](302-bridging-toio-runnow.md)).
- **`IOResult[A]`** — *only* when there is actually an effect: I/O (LDAP, git, DB,
  files, network), mutation, clock, randomness, logging side-effects, or calling
  effectful code. If genuinely in doubt whether something is effectful, it is — but
  first check whether you can keep the piece pure.

Rule of thumb: a function that merely *transforms data* should be `A => PureResult[B]`
(or `A => B`), not `A => IOResult[B]`. If you typed it `IOResult` out of habit, narrow
it.

## Importing effectful code into `IOResult`

Wrap side-effecting calls — never let an exception escape:

```scala
// blocking by default (LDAP/DB/file/network):
IOResult.attempt("explain what failed")( javaApi.doThing() )      // => IO[SystemError, A]
// CPU-bound, non-blocking:
IOResult.nonBlocking("explain what failed")( pureButThrowing() )
// when the body itself returns an IOResult and may throw while building it:
IOResult.attemptZIO("explain what failed")( buildEffect() )
```

Always pass a meaningful error message; it becomes the `SystemError` shown to
operators.

### Thread pools: why `attempt` blocks by default

The ZIO runtime has **two thread pools**:

- a **CPU-bound** pool — a *fixed* number of threads (≈ number of available CPUs) for
  non-blocking, compute work;
- a **blocking** pool — for effects that may wait on I/O indefinitely; it grows a new
  thread per fiber that runs such an effect.

Putting a blocking effect on the CPU pool starves it and can **deadlock** the app.
Because Rudder has a lot of I/O code and a lot of `.runNow` entry points (see
[`302`](302-bridging-toio-runnow.md)), we deliberately make `IOResult.attempt` **blocking
by default** (`ZIO.attemptBlocking` — see its implementation in `ZioCommons.scala`). This
is *slightly wasteful* (a no-I/O effect may still land on the blocking pool), but it is
the safe choice that avoids deadlocks. ZIO 2.1 dropped its automatic blocking detection
in favour of a heuristic that didn't work for us, so we set blocking back explicitly.

So choose the importer by the work:

- `IOResult.attempt(...)` — **default**, for anything that may block (LDAP, DB/doobie,
  files, network, most Java APIs). When unsure, use this.
- `IOResult.nonBlocking(...)` — only for genuinely CPU-bound, non-blocking work that
  throws.

### Caveat: ZIO threads and `ThreadLocal` (Spring Security)

Because effects run on ZIO's own managed threads (and fibers can move between threads),
**Java frameworks that carry context in a `ThreadLocal` don't see it inside a ZIO
effect**. The concrete case is **Spring Security**, whose `SecurityContextHolder` is
thread-local: code executed inside a ZIO effect can *lose the authenticated principal*.
This caused a real bug (lost Spring authentication when running in a ZIO context),
addressed in PR #7188 and the background to ADR
[`28452-avoid-currentuser-for-querycontext-in-lift-snippets`](../../../adr/webapp/28452-avoid-currentuser-for-querycontext-in-lift-snippets.md)
(see [`600`](600-security-in-depth.md)). When bridging ZIO with thread-local-based code,
capture the needed context *before* entering the effect and pass it explicitly — don't
rely on it being readable from inside.

## Composing

Use normal ZIO combinators: `map`, `flatMap`/for-comprehension, `foreach`,
`foreachPar`, `ZIO.foreach`, `catchAll`, `mapError`, `chainError` (see
[`301`](301-error-model.md)). Prefer `ZIO.foreach`/`accumulate*` over manual folds.

```scala
for {
  raw   <- repo.getRaw(id)                 // IOResult[Raw]
  value <- parse(raw).toIO                 // PureResult -> IOResult
  _     <- repo.save(value)                // IOResult[Unit]
} yield value
```

## Mutable state & concurrency

Never use `var` / `synchronized` / mutable collections for shared state. Use ZIO's
concurrency primitives:

- **`Ref`** for in-memory state / caches — atomic `get`/`update`/`modify`:
  ```scala
  for {
    cache <- Ref.make(Map.empty[NodeId, NodeConfigurationHash])
    _     <- cache.update(_ + (id -> hash))
    all   <- cache.get
  } yield all
  ```
- **`Semaphore`** to bound concurrency / serialize a critical section:
  `semaphore.withPermits(1) { effect }`.
- A long-lived cache held in a field may initialise its `Ref` once at construction with
  `Ref.make(...).runNow` — this is one of the few **sanctioned `.runNow`** uses
  (creating the cell, not running business logic); see
  [`302`](302-bridging-toio-runnow.md). Example:
  `rudder-core/.../nodeconfig/NodeConfigurationCacheRepository.scala`.

## Don't

- Don't run the effect (`.runNow`) deep inside business logic — keep it as a value and
  push the run to the edge (see [`302`](302-bridging-toio-runnow.md)).
- Don't use `ZLayer`/`ZIO[R, …]` with a non-`Any` `R` for wiring services. (The only
  non-`Any` `R` we accept is `Scope` for resources. One module — `rudder-rest/.../lift/
  SettingsApi.scala` — threads a service through the env with `provideLayer`; treat it
  as a **dispreferred legacy exception**, not a template. Pass collaborators by
  constructor instead, see [`102`](102-traits-and-dependency-injection.md).)
- Don't reach for extra ZIO modules — scope is `zio` core + `zio-json` only
  (see [`700`](700-dependencies-ecosystem.md)).
- Don't swallow errors with `.orDie`/`catchAll(_ => unit)` unless it is genuinely
  unrecoverable and logged.
