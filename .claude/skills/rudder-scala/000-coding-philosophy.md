# 000 — Coding philosophy, in Scala

> **Read [`rudder-principles`](../rudder-principles/SKILL.md) first.** The cross-language
> principles — the data model is the design, parse at the edge, fix the root cause, don't
> repeat yourself past twice, WYSIWYG signatures, name domain concepts as types (and
> propose the zero-cost form), weigh hot-path cost while planning, less code, the
> nominal/error/defect classification, comments, tests as a design tool, security as a
> design constraint, and the long-lived-codebase / up-merge rules — live there and are
> **not repeated here**.

This file holds what is **specific to Scala** in Rudder: how those principles are expressed
with `case class`/`sealed trait`, `IOResult`, and the legacy we are leaving.

Rudder is a long-lived codebase (Scala 2.8 in 2009 → Scala 3 today). We migrate **step by
step** toward a more functional style with **ZIO** as the effect handler. New and changed
code follows these conventions; we do not rewrite untouched legacy code just to modernize
it, but we leave every file we touch a little better.

## Immutability, and its two carve-outs

- **Immutable data.** Model with `case class`/`sealed trait`. No `var`, no in-place
  mutation of collections in new domain/service code. To "change" a value, produce a new
  one (see [`403`](403-quicklens-updates.md)); for *shared* state use `Ref`/`Semaphore`
  (see [`300`](300-effects-zio-ioresult.md)).
- **Performance carve-out (allowed).** `var` and mutable collections are fine in new
  performance-sensitive code **provided the mutation is purely internal to a well-defined
  scope and the API stays functional** — e.g. a method that takes a `Chunk`, builds its
  result with a local mutable array, and returns a `Chunk`. The mutation must not escape
  that scope. This is the concrete form of
  [principle 7](../rudder-principles/SKILL.md#7-weigh-hot-path-cost--while-planning-not-after):
  it applies on a **named hot path**, not on a vague suspicion, and it never changes the
  shape of the public API.
- **`var` as a class property is highly discouraged.** New uses must be clearly justified
  (benchmark, memory-footprint computation, …) — not a default.
- **Legacy carve-out.** The Lift UI layer (`rudder-web/.../snippet`, `web/model`,
  `web/comet`) uses `var` heavily; don't add to it and don't rewrite it on sight. Some
  perf-critical core classes (e.g. `ComplianceLevel`) also hold mutable state — see the
  rules above before following suit. Everywhere else, no `var`.

## Effects as values

Anything that mutates state, does I/O, talks to the network, filesystem, LDAP, DB, clock,
randomness… is an *effect* and must be reified as `IOResult` (see
[`300`](300-effects-zio-ioresult.md)). Pure computation that can fail uses
`PureResult[A] = Either[RudderError, A]`.

> The effect type is `com.normation.errors.IOResult[A] = ZIO[Any, RudderError, A]`.

Prefer returning `Option`/`Either`/`IOResult` over throwing. Throwing is reserved for
truly exceptional, unrecoverable situations, and is then caught at the boundary
(`IOResult.attempt`).

## No `return`

`return` is **forbidden**. A function is an expression that evaluates to its value; use
`if`/`match`/combinators so the last expression *is* the result. Early-`return` control
flow is a smell — model it with `Option`/`Either`/`IOResult` and the matching combinators.

## `null`: handle it at the edge, visibly

We don't use `null` in our own code — model absence with `Option`. The *only* place to
guard against `null` is the **system edge**, where untrusted or Java code can hand one in
(a Java API return, a deserialized value). Handle it there **once**; the rest of the code
then assumes non-null by construction. This is
[parse-don't-validate](../rudder-principles/SKILL.md#2-parse-at-the-edge-the-core-is-pure-structure)
applied to nullability (see also [`201`](201-parse-dont-validate.md)). Don't sprinkle null
checks through the domain.

Make the null handling **visible in the code** — typically a `match` on `null` — so a
future reader sees that this `null` case was deliberately handled, and that the resulting
`Option` exists *because of nullability at the boundary*, not because it is a business
optional. A bare `Option(javaCall())` hides that intent.

```scala
// clear: the null was handled here, and that is WHY this is an Option
val name: Option[String] = javaApi.getName() match {
  case null => None
  case s    => Some(s)
}

// avoid: reader can't tell a null-guard from a genuine domain optional
val name = Option(javaApi.getName())
```

Match the type to the *meaning* of the missing value:

- a **genuine domain optional** ("this value may legitimately be absent") → `Option`,
  modelled as such for that reason;
- an **unexpected `null` that is an error** (the boundary promised a value and broke its
  contract) → don't swallow it into a `None`; fail with `PureResult`/`IOResult` (e.g. an
  `Inconsistency`/`Unexpected`, see [`301`](301-error-model.md)). `Option` is reserved for
  *absence of a value*, not for *error*.

```scala
// null here is a contract violation, not an optional → it's an error
javaApi.getRequiredId() match {
  case null => Inconsistency("provider returned a null id").fail   // IOResult
  case id   => id.succeed
}
```

## WYSIWYG signatures, the Scala form

[Principle 5](../rudder-principles/SKILL.md#5-signatures-tell-the-truth-wysiwyg) in Scala
terms — the Rudder idiom is "longer but naively explicit":

```scala
def getUserFromDB(id: UserId): IOResult[Option[User]]
```

A reader sees exactly what is required (a parsed `UserId`, not a `String`) and everything
that may happen (an effect that can fail; a user that may not exist). Errors and absence
are a deliberate, typed part of the contract, not a trap (see [`301`](301-error-model.md)).

The Scala-specific mechanics: precise input types come from
[`001`](001-scala3-idioms.md)/[`400`](400-domain-case-classes.md), "can fail / does I/O" is
`IOResult[...]`/`PureResult[...]` ([`300`](300-effects-zio-ioresult.md)), and several
outcomes is a `sealed trait` ADT ([`400`](400-domain-case-classes.md)).

**No bare `Boolean` (or `String`/`Int`) for a domain concept.** A boolean parameter/field
ill-informs intent — `foo(true, false)` says nothing at the call site — and it is not
future-proof: it can never grow a third case. Model the concept as a small **`enumeratum`**
ADT instead, e.g. `ChangeRequestAuthorship` (`Author`/`NotAuthor`) rather than
`isCreator: Boolean`. Plain on/off *config toggles* may stay `Boolean`; the rule is about
concepts that flow through signatures and the domain. See
[principle 6](../rudder-principles/SKILL.md#6-name-domain-concepts-as-types--and-propose-the-zero-cost-form),
[`001`](001-scala3-idioms.md), [`400`](400-domain-case-classes.md).

## No type-level acrobatics — get the power from libraries

Scala 3 is very powerful. We **use** that power — but we want it to come from
**third-party libraries** that have already paid the complexity cost: `zio` for effects,
`chimney` for transformations, `quicklens` for updates, `zio-json` for (de)serialization
(see [`700`](700-dependencies-ecosystem.md)).

- Avoid hand-rolled type-class hierarchies, heavy `implicit`/`given` resolution puzzles,
  match types, or macro tricks in business code. If you find yourself fighting the type
  system, step back and pick the boring solution.
- A `given` that just wires a library's derivation (e.g. `derives JsonCodec`) is fine and
  encouraged; a `given` that encodes clever business logic is a smell.

Note the interaction with
[principle 6](../rudder-principles/SKILL.md#6-name-domain-concepts-as-types--and-propose-the-zero-cost-form):
an `opaque type` is **not** type-level acrobatics — it is a plain zero-cost newtype, and it
is the preferred form for a new single-value wrapper ([`001`](001-scala3-idioms.md)).

## Simplicity, in this codebase

[Principle 8](../rudder-principles/SKILL.md#8-less-code-is-better-code) applied here: before
adding a class, a layer or an abstraction, ask whether the code is simpler without it — we
are *attached but not integrist* about DDD/hexagonal (see
[`101`](101-architecture-ddd-hexagonal.md)). Delete more than you add, dependencies
included (see [`700`](700-dependencies-ecosystem.md)).

## The legacy we are leaving

Joda-Time, Lift `Box`, and `.runNow` at call sites are *legacy we are leaving* — prefer
`java.time`, `IOResult`, and pushing effects outward instead (see
[`500`](500-datetime-java-time.md), [`302`](302-bridging-toio-runnow.md)). `var`-based Lift
snippets are the third — see the legacy carve-out above.

Apply this within the budget described in
[the long-lived-codebase rules](../rudder-principles/SKILL.md#working-in-a-long-lived-codebase):
we maintain several release branches at once (e.g. `branches/rudder/8.3`,
`branches/rudder/9.1`, `branches/rudder/9.2`) and up-merge fixes forward, so **minimize
incidental diff — but never compromise the quality of the change itself.**
