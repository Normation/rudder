# 302 — Bridging legacy code: `.toIO`, `.toBox`, `.runNow`

Rudder has **no single effect entry point**. Effects are run in many places,
especially where ZIO code meets historical, non-effect-managed code (Lift web layer,
Java callers). We bridge with thin wrappers and we **minimize `.runNow`**.

All of these live in `com.normation.errors` / `com.normation.box` / `com.normation.zio`
(`utils/.../ZioCommons.scala`).

## Into the effect world — `.toIO` (preferred direction)

Lift a non-ZIO result into `IOResult` so it composes with the rest:

```scala
pureResult.toIO            // PureResult[A]        => IOResult[A]
either.toIO                // Either[String, A]    => IOResult[A]  (Left -> Inconsistency)
option.notOptional("msg")  // Option[A]            => IOResult[A]  (None -> Inconsistency)
box.toIO                   // lift.common.Box[A]   => IOResult[A]  (legacy Lift Box)
chimneyResult.toIO         // chimney partial.Result[A] => IOResult[A]
```

Use `.toIO` to *stay* in `IOResult` as long as possible — that's how we keep effect
management consistent.

**Lift late, stay at the least powerful level.** `.toIO` is a one-way step *up* in
power: a `PureResult` keeps its "no side effects" promise, an `IOResult` doesn't. So
keep pure logic as `PureResult` (or plain values) and call `.toIO` only at the point you
actually need to compose with an effect — don't widen a whole pipeline to `IOResult`
just because one step at the end is effectful (see
[`300`](300-effects-zio-ioresult.md#when-to-use-which--prefer-the-least-powerful)).

When you lift across a **sub-system boundary**, add context with `chainError` so the
error stays meaningful to its new audience instead of leaking a raw lower-level cause
(each layer contributes a hint — see [`301`](301-error-model.md)):

```scala
parseConfig(raw).toIO.chainError(s"Error reading settings for node '${id.value}'")
```

## Out to legacy — `.toBox` (Lift) and Java interop

When you must hand a result to legacy Lift code that expects a `Box`:

```scala
ioResult.toBox             // IOResult[A]   => Box[A]    (runs the effect!)
pureResult.toBox           // PureResult[A] => Box[A]
either.toBox               // Either[String, A] => Box[A]
```

`ioResult.toBox` *runs the effect* under the hood (`ZioRuntime.runNow`) and maps
`RudderError` to a Lift `Failure` (preserving `Chained`/`SystemError` structure). Use
it at the Lift boundary, not inside business logic.

## Running effects — `.runNow` (minimize!)

```scala
ioResult.runNow                         // A           (throws fiber failure on error)
ioResult.runNowLogError(logger)         // Unit        (logs RudderError, discards result)
ioResult.runOrDie(err => new Exc(...))  // A           (throws your exception on error)
```

**Every `.runNow` breaks the global consistency of effect management.** Treat each one
as a small debt:

- It belongs at a **true boundary** — a Lift snippet/REST handler edge, a Java-called
  shim, app bootstrap — never in the middle of a service.
- When you find yourself wanting `.runNow` mid-logic, the fix is almost always to
  return `IOResult` and let the caller compose; push the run further out.
- Prefer `.toBox`/`.runNowLogError` at Lift edges over a bare `.runNow` so errors are
  turned into something the UI/log can use rather than thrown.
- When touching legacy code, **reduce** the number of `.runNow` if you reasonably can;
  don't add new ones casually.

## Resources

For acquire/release use `IOManaged` / `ZIO.acquireRelease` (in `com.normation.box`),
not manual try/finally.
