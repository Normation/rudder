# 303 — Logging

Logging is an effect: in new code it stays inside `IOResult`/`UIO` rather than being a
hidden side effect. We log through ZIO-aware loggers and are **migrating away from the
Lift `extends Logger`** style.

## Use a named pure logger derived from `NamedZioLogger`

`com.normation.NamedZioLogger` (`utils/.../ZioCommons.scala`) is the effectful logger:
its `trace/debug/info/warn/error` return `UIO[Unit]`, so log calls compose inside an
`IOResult` for-comprehension and never throw into the app error channel.

Create one **named** logger object per area, by convention suffixed `Pure`:

```scala
object ComplianceDebugLoggerPure extends NamedZioLogger {
  def loggerName: String = "explain_compliance"
}

// usage, inside an effect:
for {
  _   <- ComplianceDebugLoggerPure.debug(s"Computing compliance for ${id.value}")
  res <- compute(id)
  _   <- ComplianceDebugLoggerPure.info(s"done: ${res.size} entries")
} yield res
```

- `def loggerName` (use `def`/`lazy val`, **not** `val` — a `val` triggers
  `UninitializedFieldError`).
- `NamedZioLogger("some.name")` gives an ad-hoc instance when a dedicated object is
  overkill.
- Named loggers live together under `com.normation.rudder.domain.logger` (e.g.
  `ApplicationLogger`, `PolicyGenerationLogger`); add new ones there.

## Don't break the effect for logging

- Log errors **without** propagating them to the app error channel — `NamedZioLogger`
  already isolates logging failures (`logAndForgetResult`). Don't `catchAll` real
  business errors just to log.
- Need to log around a value you must keep? Use `ZIO.tap`/`tapError`:
  ```scala
  repo.load(id)
    .tapError(err => LoggerPure.error(s"load failed: ${err.fullMsg}"))
  ```

## Legacy: Lift `extends Logger`

Older code uses `object X extends net.liftweb.common.Logger` (eager, non-effectful).
**Don't add new ones.** When you touch such code, prefer introducing a `*Pure`
`NamedZioLogger` and routing new log calls through it. A legacy `Logger` and its `Pure`
counterpart can coexist on the same logical name during migration (see
`DebugComplianceLogger.scala`).
