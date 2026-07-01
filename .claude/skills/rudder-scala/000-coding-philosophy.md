# 000 — Coding philosophy

The spirit behind every other topic in this skill.

## Functional programming, pragmatically

- **Immutable data.** Model with `case class`/`sealed trait`. No `var`, no in-place
  mutation of collections in new domain/service code. To "change" a value, produce a
  new one (see [`403`](403-quicklens-updates.md)); for *shared* state use `Ref`/
  `Semaphore` (see [`300`](300-effects-zio-ioresult.md)).
  - **Performance exception (allowed):** `var` and mutable collections are fine in
    new performance-sensitive code **provided the mutation is purely internal to a
    well-defined scope and the API stays functional** — e.g. a method that takes a
    `Chunk`, builds its result with a local mutable array, and returns a `Chunk`. The
    mutation must not escape that scope.
  - **`var` as a class property is highly discouraged.** New uses must be clearly
    justified (benchmark, memory-footprint computation, …) — not a default.
  - **Legacy:** the Lift UI layer (`rudder-web/.../snippet`, `web/model`, `web/comet`)
    uses `var` heavily; don't add to it and don't rewrite it on sight. Some
    perf-critical core classes (e.g. `ComplianceLevel`) also hold mutable state — see
    the rules above before following suit. Everywhere else, no `var`.
- **Effects as values.** Anything that mutates state, does I/O, talks to the network,
  filesystem, LDAP, DB, clock, randomness… is an *effect* and must be reified as
  `IOResult` (see [`300`](300-effects-zio-ioresult.md)). Pure computation that can
  fail uses `PureResult[A] = Either[RudderError, A]`.
- **Total functions where you can.** Prefer returning `Option`/`Either`/`IOResult`
  over throwing. Throwing is reserved for truly exceptional, unrecoverable situations
  and is then caught at the boundary (`IOResult.attempt`).
- **No `return`.** `return` is **forbidden**. A function is an expression that evaluates
  to its value; use `if`/`match`/combinators so the last expression *is* the result.
  Early-`return` control flow is a smell — model it with `Option`/`Either`/`IOResult`
  and the matching combinators instead.
- **No `null`, and check it only at the edge — explicitly.** We don't use `null` in our
  own code — model absence with `Option`. The *only* place to guard against `null` is the
  **system edge**, where untrusted/Java code can hand one in (a Java API return, a
  deserialized value). Handle it there **once**, and the rest of the code then assumes
  non-null by construction (this is parse-don't-validate for nullability, see
  [`201`](201-parse-dont-validate.md)). Don't sprinkle null checks through the domain.

  Make the null handling **visible in the code** — typically a `match` on `null` — so a
  future reader sees that this `null` case was deliberately handled, and that the
  resulting `Option` exists *because of nullability at the boundary*, not because it is a
  business-model optional value. A bare `Option(javaCall())` hides that intent.

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
  - an **unexpected `null` that is an error** (the boundary promised a value and broke
    its contract) → don't swallow it into a `None`; fail with `PureResult`/`IOResult`
    (e.g. an `Inconsistency`/`Unexpected`, see [`301`](301-error-model.md)). `Option`
    is reserved for *absence of a value*, not for *error*.

  ```scala
  // null here is a contract violation, not an optional → it's an error
  javaApi.getRequiredId() match {
    case null => Inconsistency("provider returned a null id").fail   // IOResult
    case id   => id.succeed
  }
  ```

## Don't lie in your code (WYSIWYG signatures)

A signature should tell the whole truth — what it needs and what can happen — with no
hidden surprises. Make contracts **WYSIWYG**:

- **Structure inputs.** Take the precise type, not a stand-in. `getUser(id: UserId)`,
  not `getUser(id: String)` (see [`001`](001-scala3-idioms.md)).
- **Enumerate outputs.** All expected outcomes live in the result type — `Option` for
  "maybe absent", an ADT for several cases — not hidden behind exceptions.
- **No hidden constraints, dependencies, or side effects.** If it does I/O or can fail,
  say so in the type: `IOResult[...]` / `PureResult[...]` (see [`300`](300-effects-zio-ioresult.md)).
- **Total functions.** Prefer functions defined for every value of their input types;
  push partiality into the types (parsed inputs, `Option`/`Either` outputs).

The Rudder idiom is "longer but naively explicit", e.g.
`getUserFromDB(id: UserId): IOResult[Option[User]]` — a reader sees exactly what's
required and what may happen. Errors and absence are then a deliberate, typed part of
the contract, not a trap (see [`301`](301-error-model.md)).

## Less code is better code

- Optimize for **fewer lines and fewer moving parts**, not for cleverness or for
  "completing the pattern". A 5-line solution that a teammate understands at a glance
  beats a 50-line "architecturally pure" one.
- Before adding a class, a layer, or an abstraction, ask whether the code is simpler
  without it. We are *attached but not integrist* about DDD/hexagonal
  (see [`101`](101-architecture-ddd-hexagonal.md)).
- Delete more than you add. This applies to code and to dependencies
  (see [`700`](700-dependencies-ecosystem.md)).

## Duplication: the rule of three

- **Duplicating once is fine** — and is *preferred* over prematurely adding a
  parameter, an abstraction, or a shared helper. Concrete and simple first.
- A **second** occurrence is useful, not alarming: it reveals which parts actually vary
  (the "free axes") versus what is truly common. You usually can't see the right
  abstraction until you've seen it twice.
- At the **third** occurrence, stop and consolidate the logic into a single point. Now
  the abstraction is informed by real cases instead of guessed up front.
- Corollary: don't build a generic/parameterized solution for a single (or imagined)
  use. Over-engineering to avoid duplication is worse than the duplication.

## No type-level acrobatics

- Scala 3 is very powerful. We **use** that power — but we want it to come from
  **third-party libraries** that have already paid the complexity cost: `zio` for
  effects, `chimney` for transformations, `quicklens` for updates, `zio-json` for
  (de)serialization.
- Avoid hand-rolled type-class hierarchies, heavy `implicit`/`given` resolution
  puzzles, match types, or macro tricks in business code. If you find yourself
  fighting the type system, step back and pick the boring solution.
- A `given` that just wires a library's derivation (e.g. `derives JsonCodec`) is
  fine and encouraged; a `given` that encodes clever business logic is a smell.

## Comments explain *why*, not *how*

- Write **short, descriptive** comments that capture the **why** — intent, rationale,
  trade-offs — or a genuinely **noticeable point** (a subtlety, an invariant, a gotcha,
  a non-obvious edge case, a link to context).
- **Don't** comment the **how**: a comment that just rephrases what the code already
  says in English is noise. The code is the source of truth for *how*; keep it readable
  enough to not need narration.
- Prefer making the code self-explanatory (good names, small functions) over adding a
  comment. Reach for a comment when the reasoning can't live in the code itself.

```scala
// system object must ALWAYS be ENABLED, otherwise policy generation skips it (RUDDER-1234)
def isEnabled: Boolean = _isEnabled || policyTypes.isSystem

// BAD — restates the code:
// return true if enabled or if it is a system type
def isEnabled: Boolean = _isEnabled || policyTypes.isSystem
```

## Unit testing is mandatory — and a design tool

- **Tests are not optional.** Code lands with tests (see [`900`](900-testing.md) for the
  how). Untested code is treated as unfinished.
- **Tests shape the architecture.** Testability is a design signal: if something is
  hard to test in isolation, it's usually a smell — too coupled to persistence/user
  input/infrastructure, or one unit doing too many things with too many concepts. Make
  it testable by *fixing the design* (pure logic behind a trait, effects pushed out —
  see [`101`](101-architecture-ddd-hexagonal.md)), not by reaching for heavyweight test
  machinery. More reasons tests earn their keep:
  - **A test materializes the goal.** Writing the expected input → output makes the
    problem concrete; it often clarifies *what* you're actually solving before (and
    while) you solve it.
  - **A test documents intent.** It's executable documentation for other devs: it shows
    how the code is meant to be used and what behaviour is expected — and, unlike prose,
    it can't silently drift out of date.
  - **Tests tighten the feedback loop.** A fast, local check beats redeploying and
    clicking through the UI. A quicker feedback loop is always good for DX and for
    converging efficiently toward a solution.
  - **Tests are a regression ledger.** Over time the suite becomes a record of every
    edge case and bug we've fixed — encoding "don't reintroduce this" so old defects
    stay dead. When you fix a bug, add the test that would have caught it.
- **We don't do TDD** (in the hyped, test-first-always sense). Write tests alongside the
  code, driven by the design — not as a ritual that dictates it.
- **100% coverage is a non-goal.** Don't chase a number. Concentrate effort where it
  pays:
  - **business logic** — the rules, computations, parsing, invariants;
  - **"broader unit tests"** — exercise a slice of logic end-to-end, but with
    *controllable* inputs and **no** real I/O, so they stay **fast, deterministic, and
    fully automated**. These catch integration mistakes between units without the cost
    and flakiness of touching real systems.
- Skip tests that only assert the framework/compiler (trivial getters, pure data
  shuffling with no logic).

## Migration mindset

- The codebase spans 15+ years. **Don't** mass-rewrite legacy on sight.
- **Do** apply current conventions to every file you create or substantially edit,
  and nudge neighbouring code toward them when it's cheap and safe.
- Joda-Time, Lift `Box`, and `.runNow` at call sites are *legacy we are leaving* —
  prefer `java.time`, `IOResult`, and pushing effects outward instead
  (see [`500`](500-datetime-java-time.md), [`302`](302-bridging-toio-runnow.md)).

### Concurrent branches & up-merge

We maintain several release branches at once (e.g. `branches/rudder/8.3`,
`branches/rudder/9.1`, `branches/rudder/9.2`) and **up-merge** bug fixes from the oldest
maintained branch up to the current one. A fix touching code that has diverged between
branches creates merge conflicts, so:

- **Be mindful of up-merge cost.** When fixing a bug on an old branch, prefer changes
  that will merge cleanly forward — keep the diff focused, and avoid gratuitously
  reshaping surrounding code that has already moved on in newer branches.
- **But clean code wins.** This constraint *tempers* how much unrelated churn we add;
  it does **not** justify writing worse code. We will **not** introduce convoluted
  bridging/compat shims, pick worse names, or duplicate logic just to dodge a merge
  conflict. Resolve the conflict at merge time instead.
- Net: minimize incidental diff, never compromise the quality of the change itself.
