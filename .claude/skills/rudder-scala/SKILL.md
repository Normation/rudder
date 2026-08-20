---
name: rudder-scala
description: >-
  Conventions and idioms for writing Scala in the Rudder codebase (webapp/sources).
  Use whenever reading, writing, reviewing, or refactoring Scala in the rudder repo.
  Covers functional style, ZIO effects and the RudderError model (IOResult), DDD /
  hexagonal architecture, repositories, parse-don't-validate, zio-json / chimney /
  quicklens data handling, java.time, security-in-depth, and dependency policy.
---

# Writing Scala in Rudder

Rudder is a long-lived codebase (Scala 2.8 in 2009 → Scala 3 today). We migrate
**step by step** toward a more functional style with **ZIO** as the effect handler.
New and changed code follows the conventions below; we do not rewrite untouched
legacy code just to modernize it, but we leave every file we touch a little better.

**Scope — applies to all the Rudder Scala repos.** These conventions hold for the Scala
code in `rudder`, **`rudder-plugins`, and `rudder-plugins-private`** alike (the plugin
repos build on rudder-core and use the same stack: `IOResult`/`RudderError`, zio-json,
chimney, quicklens, enumeratum, the plugin API framework, etc.). This skill file lives
in the `rudder` repo, and its links to ADRs and example code are relative to that repo —
but when you write Scala in either plugins repo, **follow this skill too** (look up the
referenced rudder code/ADR in the `rudder` checkout). Plugin-specific points: the
dynamic plugin-status API framework ([`103`](103-rest-api-and-endpoints.md)) and license
parsing live in the plugin repos.

## Read the shared principles first

The **cross-language engineering principles** — which apply to every Rudder codebase, not
just Scala — live in **[`rudder-principles/SKILL.md`](../rudder-principles/SKILL.md)**. Read
that first, and keep it applied for the whole task: the data model is the design, parse at
the edge (pure core), fix the **root cause** rather than the visible symptom, don't repeat
yourself past twice, WYSIWYG signatures, name domain concepts as types and **propose the
zero-cost form**, weigh **hot-path cost while planning**, less code, nominal/error/defect,
comments, tests as a design tool, security as a design constraint, and the up-merge rules.

Those principles are *not* restated here. This skill is their **Scala expression** plus the
stack mechanics. Where a principle has a specific Scala form, the topic file says so:

| Principle | Scala form |
|---|---|
| Parse at the edge, pure core | [`201`](201-parse-dont-validate.md), nulls in [`000`](000-coding-philosophy.md) |
| Data model is the design; concepts as types | [`001`](001-scala3-idioms.md) (`opaque type`), [`400`](400-domain-case-classes.md) |
| Signatures tell the truth | `IOResult`/`PureResult` — [`000`](000-coding-philosophy.md), [`300`](300-effects-zio-ioresult.md) |
| Nominal / error / defect | `RudderError` — [`301`](301-error-model.md) |
| Hot-path cost | the `var` carve-out in [`000`](000-coding-philosophy.md) |
| Security as a design constraint | [`600`](600-security-in-depth.md), [`601`](601-web-and-output-security.md), [`602`](602-authentication-and-authorization.md) |
| Tests as a design tool | [`900`](900-testing.md) |

## Golden rules — Scala-specific (always apply)

1. **Functional first.** Immutable data, no shared mutable state, effects reified as
   values. Any mutation or I/O *must* be wrapped in `IOResult` (see `300`). No `return`,
   no `null` outside the edge (see `000`).
2. **Dumb data, smart companions.** Business objects are plain `case class`es with as
   few methods as possible. Serialization, conversion and mapping live in companion
   objects or `extension` methods (see `001`, `400`).
3. **Program to traits.** Every service/repository has a trait defining its API, even
   with a single implementation. DI is constructor-only, wired in `RudderConfig`
   (see `102`).
4. **Get the power from libraries, not from type-level machinery.** Scala 3 is powerful —
   lean on that power, but take it from zio, chimney, quicklens, zio-json rather than
   hand-rolled implicit/type-level puzzles (see `000`, `700`). An `opaque type` is *not*
   acrobatics: it is the preferred new single-value wrapper (see `001`).
5. **Persistence goes through a repository** — the single point of change for a
   concept's storage (see `200`).
6. **Be strict about dependencies.** Apache2/BSD-compatible licenses only; prefer
   removing deps over adding them (see `700`). *(This is the JVM rule — the Rust
   workspace has its own, `deny.toml`-based gate.)*

## How to use this skill

Before doing substantial work in a given area, read the matching topic file. Files
are named `NNN-topic.md`; the **first digit** is the subject area (table below), the
other two digits identify the topic within it.

| 1st digit | Subject area |
|-----------|--------------|
| 0 | Generalities — coding philosophy & Scala 3 idioms |
| 1 | Architecture — DDD, hexagonal, traits & dependency injection |
| 2 | Persistence — repositories, parse-don't-validate |
| 3 | Effects & errors — ZIO, `IOResult`, bridging legacy code |
| 4 | Data & serialization — case classes, zio-json, chimney, quicklens |
| 5 | Datetime — `java.time` |
| 6 | Security — defense in depth, web/output safety, authn/authz |
| 7 | Dependencies & ecosystem |
| 8 | Build, tooling & formatting |
| 9 | Testing |

### Topic index

- [`000-coding-philosophy.md`](000-coding-philosophy.md) — the Scala form of the shared principles: immutability + its `var` carve-outs, effects as `IOResult`, no `return`, `null` at the edge, no type acrobatics
- [`001-scala3-idioms.md`](001-scala3-idioms.md) — companions, extensions, `derives`, `given`, opaque types, minimizing imports
- [`100-package-layout.md`](100-package-layout.md) — bounded-context-first packages, not technical-layer; migrating off legacy `domain`/`repository`/`services`
- [`101-architecture-ddd-hexagonal.md`](101-architecture-ddd-hexagonal.md) — bounded contexts, hexagonal, "pragmatic DDD"
- [`102-traits-and-dependency-injection.md`](102-traits-and-dependency-injection.md) — program-to-trait, constructor DI, `RudderConfig`
- [`103-rest-api-and-endpoints.md`](103-rest-api-and-endpoints.md) — declarative `EndpointSchema`, versioning, per-endpoint `authz`, lift handlers
- [`104-bootstrap-and-migrations.md`](104-bootstrap-and-migrations.md) — `BootstrapChecks` early/end phases, self-managed async DB/schema migrations, `Boot.scala`
- [`200-persistence-repositories.md`](200-persistence-repositories.md) — repository as single point of change, optional persistence layer
- [`201-parse-dont-validate.md`](201-parse-dont-validate.md) — typed parsing at I/O boundaries
- [`300-effects-zio-ioresult.md`](300-effects-zio-ioresult.md) — `IOResult` / `PureResult`, when & how to use ZIO
- [`301-error-model.md`](301-error-model.md) — `RudderError`, `Chained`, `Accumulated`, domain errors
- [`302-bridging-toio-runnow.md`](302-bridging-toio-runnow.md) — `.toIO`, `.toBox`, minimizing `.runNow`
- [`303-logging.md`](303-logging.md) — `NamedZioLogger` pure loggers, migrating off lift `extends Logger`
- [`400-domain-case-classes.md`](400-domain-case-classes.md) — dumb case classes, value/opaque wrappers, ADTs
- [`401-json-zio-json.md`](401-json-zio-json.md) — `derives JsonCodec`, manual codecs, discriminators
- [`402-chimney-transformers.md`](402-chimney-transformers.md) — mapping between representations
- [`403-quicklens-updates.md`](403-quicklens-updates.md) — `.modify(...).setTo(...)` instead of `.copy`
- [`404-serialization-contracts.md`](404-serialization-contracts.md) — user-facing serialization is a contract; DTOs + chimney, stable tokens, test-enforced
- [`500-datetime-java-time.md`](500-datetime-java-time.md) — `java.time`, migrating off joda
- [`600-security-in-depth.md`](600-security-in-depth.md) — `SecurityError`, tenants, path traversal, defense in depth
- [`601-web-and-output-security.md`](601-web-and-output-security.md) — `XmlSafe` (XXE), Lift `JsRaw`/XSS, CSRF, CSP, sessions
- [`602-authentication-and-authorization.md`](602-authentication-and-authorization.md) — Spring auth vs Rudder authz, BouncyCastle, tokens, RBAC/ACL `AuthorizationType`
- [`700-dependencies-ecosystem.md`](700-dependencies-ecosystem.md) — license policy, minimizing deps, ZIO scope
- [`800-build-and-formatting.md`](800-build-and-formatting.md) — maven/spotless/scalafmt, `-Werror`, license headers
- [`900-testing.md`](900-testing.md) — specs2 + JUnitRunner, zio-test for new code, trait-based test doubles

> The effect type is `com.normation.errors.IOResult[A] = ZIO[Any, RudderError, A]`.

## Sources of truth

The **shared principles** are in
[`rudder-principles/SKILL.md`](../rudder-principles/SKILL.md) — including the
error-management philosophy behind `RudderError`/`IOResult` (nominal cases vs errors vs
defects, WYSIWYG contracts, errors-as-signal), which comes from the talk **"Systematic
error management in application"** (DevoxxFR 2021, F. Armand). Its Scala mechanics are in
[`301`](301-error-model.md).

This skill summarizes and operationalizes decisions; the **authoritative records are
the ADRs** in [`rudder/adr/webapp/`](../../../adr/webapp/) (and some in
[`rudder/adr/system/`](../../../adr/system/)). When a topic touches an ADR, the topic
file cites it — **follow the link and read the ADR** for the full context and rationale,
and prefer the ADR if it ever conflicts with the summary here. If you make a decision
that isn't captured yet, add an ADR (see [`adr/template.md`](../../../adr/template.md))
and then update the matching topic file. ADRs already reflected here include:

- `18879` zio-json is the only JSON library → [`401`](401-json-zio-json.md)
- `28515` use `java.time` (prefer `Instant`) and `27084` explicit-timezone/RFC-3339
  dates → [`500`](500-datetime-java-time.md)
- `28452` safe `QueryContext` in Lift snippets (`SecureDispatchSnippet`) → [`600`](600-security-in-depth.md)
- `28612` central plugin-status checks for menus/APIs → [`103`](103-rest-api-and-endpoints.md)
