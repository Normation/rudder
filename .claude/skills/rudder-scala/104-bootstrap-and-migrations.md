# 104 — Bootstrap checks & self-managed migrations

Rudder manages its **own** integrity: schema and data migrations, consistency checks,
and one-time initialisations run **inside the application at startup**, not from external
scripts. The mechanism is the `BootstrapChecks` pipeline under
`rudder-web/.../bootstrap/liftweb/checks/`.

## The `BootstrapChecks` interface

A check is a small class implementing `bootstrap.liftweb.BootstrapChecks`
(`bootstrap/liftweb/BootstrapChecks.scala`):

```scala
trait BootstrapChecks {
  def description: String
  def checks(): Unit   // executed during boot — be careful with the time it takes
}
```

Checks are grouped into ordered sequences (`SequentialImmediateBootStrapChecks`, or
`OnceBootstrapChecks` for the very earliest) that log and time each step and `catchAll`
so one failure doesn't abort the whole sequence. Log through `BootstrapLogger`
(`bootchecks`, with `.Early.DB` / `.Early.LDAP` children — see [`303`](303-logging.md)).

## Two phases — pick the right one

### `earlyconfig/` — during `RudderConfig` instantiation, before services

Packages `checks.earlyconfig.db` and `checks.earlyconfig.ldap`. These run **as soon as
the DB/LDAP connection exists and before the repositories/services are built**, because
those services may depend on the changes. Wired in `RudderConfig` as immediate sequences
(e.g. `earlyDbChecks` / `earlyLdapChecks`, run with `.checks()` right there).

Use early checks for: **evolving database schemas and data layout**, LDAP layout, and
consistency that later components rely on (create/alter tables, enforce a schema,
add columns, drop obsolete tables…).

### `endconfig/` — after services/repositories are instantiated

Packages `checks.endconfig.{action,consistency,migration,onetimeinit}`. Collected into
`allBootstrapChecks` in `RudderConfig` and run from `RudderConfig.init()` via
`allBootstrapChecks.checks()` — deliberately **outside** Lift's `boot()` (which is
wrapped in a try/catch that would swallow failures).

Use end checks for: **converging data**, triggering the start of things, one-time
initialisations (default templates, instance UUID, technique-library reload…), and
migrations that need the domain services to already exist.

### Services that must start after the webapp boots

A long-lived service that should start once the webapp is up (not a check) is started in
`bootstrap/liftweb/Boot.scala#boot`, not in the checks pipeline.

## Self-managed, convergent migrations

We do **not** ship external migration scripts — the app migrates itself on startup. That
puts two requirements on every migration:

- **Convergent, idempotent, restartable.** A check may run on every boot and may be
  interrupted; it must be safe to re-run. Use `CREATE TABLE IF NOT EXISTS`,
  `ADD COLUMN IF NOT EXISTS`, guard on current state, and design so an interrupted
  migration simply resumes next boot.
- **Minimise blocking / transaction time.** Boot must not hang on a long migration.
  **Prefer async**: do the heavy work on a forked daemon fiber so boot continues, e.g.
  (`checks/earlyconfig/db/CreateTableNodeFacts.scala`):

  ```scala
  override def checks(): Unit = {
    val prog = for { _ <- createTableStatement } yield ()
    // run async so it does not block boot
    prog
      .catchAll(err => BootstrapLogger.Early.DB.error(s"... ${err.fullMsg}"))
      .forkDaemon
      .runNow
  }
  ```

  This is one of the **sanctioned `.runNow`** boundaries (see
  [`302`](302-bridging-toio-runnow.md)): the `checks()` method is a `Unit` boot hook, so
  it forks the effect and returns. The forked migration is "convergent and asynchronous:
  it does not block boot, and can be interrupted and restarted afterward."

## The hard balance

Async is the default, but **when correctness depends on the migration finishing before
the data is used, it must be synchronous** (blocking) — e.g. a schema change a service
needs the moment it starts. Split the work: do the *minimal* integrity-critical part
synchronously (and early), and fork the bulk (back-filling rows, rewriting large tables)
to run in the background. Document, per migration, what is sync and why.

## Adding a migration/check

1. Write a class extending `BootstrapChecks` in the right package
   (`earlyconfig.db`/`.ldap` if services depend on it; `endconfig.*` otherwise), with a
   clear `description` and an idempotent body.
2. Decide sync vs async (default async via `.forkDaemon`); keep the synchronous portion
   minimal.
3. Wire it into the matching sequence in `RudderConfig` (`earlyDbChecks`,
   `earlyLdapChecks`, or `allBootstrapChecks`); a separate long-running service starts in
   `Boot.scala#boot`.
4. Log via `BootstrapLogger`, and `catchAll` so a failure is logged, not fatal (unless it
   genuinely must abort startup).
5. Test it (incl. the "runs twice" / "interrupted then resumed" cases) — see
   [`900`](900-testing.md); persistence specifics in [`200`](200-persistence-repositories.md).
