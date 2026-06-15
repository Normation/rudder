# 101 — Architecture: pragmatic DDD & hexagonal

We are **attached but not integrist** about Domain-Driven Design and hexagonal
architecture. We use them to get *clear responsibilities and loose coupling*, not to
generate ceremony.

## The aspiration: pure, isolation-testable domain logic

The goal everything else serves: **business logic that is pure and can be tested in
isolation**, with **invariants we can identify and enforce**.

- **Pure.** Domain logic is a function of its inputs — no I/O, no hidden state, no
  clock/randomness baked in. Effects are pushed to the edges (repositories, services'
  boundaries) so the core is just data → data (see [`300`](300-effects-zio-ioresult.md)).
- **Testable in isolation.** Such logic needs no database, no LDAP, no HTTP, no
  mocking framework to test — you call it with values and assert on values. If a piece
  of logic can't be tested without standing up infrastructure, that's the design
  telling you it's too coupled or too big — split it (this is the testability-as-design
  signal from [`000`](000-coding-philosophy.md#unit-testing-is-mandatory--and-a-design-tool)).
- **Invariants you can enforce.** Make illegal states unrepresentable: encode rules in
  types (parsed wrappers, ADTs — see [`001`](001-scala3-idioms.md),
  [`201`](201-parse-dont-validate.md)) so the compiler upholds them, and check the rest
  in one well-identified place (a `parse`, a smart constructor, a domain function)
  rather than scattering ad-hoc checks. An invariant that lives in pure code is one you
  can actually test.

This is the *target*; legacy code won't all look like this, but new design should bend
toward it.

## What we keep from DDD / hexagonal

- **Bounded contexts.** Code is organized around business domains (nodes, rules,
  directives, campaigns, plugins, compliance/reports, properties, …). Each context
  owns its domain types and its repositories, **colocated in one package per context**
  — see [`100`](100-package-layout.md) for the directory layout.
- **Domain at the center.** Pure domain types and logic don't depend on
  infrastructure. Infrastructure (LDAP, git, DB, HTTP) depends on the domain via
  traits (ports), not the other way around.
- **Ports & adapters.** A repository *trait* is a port; its `*Impl` (LDAP, git, in
  memory…) is an adapter. Business code talks to the trait only
  (see [`102`](102-traits-and-dependency-injection.md), [`200`](200-persistence-repositories.md)).
- **Boundaries parse input.** Anything coming from outside is parsed into typed
  domain objects before the domain touches it (see [`201`](201-parse-dont-validate.md)).

## What we deliberately relax

- **Naming conventions.** We do *not* religiously use DDD vocabulary
  (Aggregate, ValueObject, Entity, Factory…) in type names. Clear, plain names win.
- **No mandatory layering ceremony.** We don't create a separate "application
  service", "domain service", "DTO", and "mapper" for every operation. If a single
  function in a repository is enough, that's the design.
- **Pattern completeness is not a goal.** Implement the *part* of the pattern that
  buys clarity or decoupling; skip the rest. Fewer, simpler types beat a textbook
  hexagon.

## Decision guide

- New concept that other code reasons about → a domain `case class`/ADT in the
  relevant bounded context.
- Need to read/write that concept somewhere → a **repository trait** + impl
  ([`200`](200-persistence-repositories.md)).
- Crossing a boundary (API, file, LDAP, CLI) → parse into domain types on the way in,
  serialize on the way out ([`201`](201-parse-dont-validate.md),
  [`401`](401-json-zio-json.md)).
- Mapping between two internal representations → chimney
  ([`402`](402-chimney-transformers.md)), not hand-written field copying.

## Services

"**Service**" is a naming convention with a precise meaning here: a **stateless**,
**single-instance** class that holds **domain logic** in the usual DDD sense — behaviour
that doesn't naturally belong to a single entity/value object (it orchestrates several
of them, or implements a domain operation/policy). It is *not* a catch-all for "any
class".

- **Stateless.** A service holds no mutable state of its own; it operates on its inputs
  and its (injected) collaborators. State lives in repositories
  ([`200`](200-persistence-repositories.md)) or, when truly needed, in `Ref`/`Semaphore`
  cells ([`300`](300-effects-zio-ioresult.md)).
- **Single instance.** There is exactly one, created as a `lazy val` in `RudderConfig`
  and wired by constructor ([`102`](102-traits-and-dependency-injection.md)) — not
  instantiated ad hoc at call sites.
- **Trait + impl.** Like everything else, a service is a **trait** (the API) with an
  implementation; effectful methods return `IOResult` ([`300`](300-effects-zio-ioresult.md)).
- **Naming.** The type is suffixed `Service` (`NodePropertiesService`,
  `WorkflowLevelService`, …); impls follow the usual `*Impl`/`Default*` forms.

```scala
trait NodePropertiesService {
  def updateAll(): IOResult[Unit]
  // ... domain operations over node properties
}

// in RudderConfig:
lazy val nodePropertiesService = new NodePropertiesServiceImpl(propertiesRepository, ...)
```

Use a service when logic spans multiple entities/repositories or expresses a domain
rule with no obvious home; don't create one just to wrap a single repository call (call
the repository) or to host pure helpers on one type (put those in its companion /
`extension`, see [`001`](001-scala3-idioms.md)).

## Coupling test

Before adding a dependency between two modules/contexts, ask: *does the domain need
this, or only an adapter?* Keep the arrows pointing inward. If a domain type starts
importing infrastructure, that's the signal to introduce (or use) a port.
