# 301 — Error model: `RudderError`

All failures flow through one error channel: **`RudderError`**. This lets modules
compose seamlessly (`IOResult`/`PureResult` both fail with `RudderError`). Each
*module* still defines its own domain error subtype for meaningful semantics.

> Foundational rationale: the talk **"Systematic error management in application"**
> (DevoxxFR 2021, F. Armand). It is the design source for this topic; the key points are
> summarized below.

## Nominal cases, errors, defects

Every interaction a piece of code models is one of three things. Classifying them
correctly is the whole game — **you, the developer, decide which is which, and it
depends on the (sub)system** (the same condition can be an error in one bounded context
and a defect in another).

- **Nominal case** — an *expected* outcome. Crucially this is **not only the happy
  path**: "the game can be won *or* lost" are both nominal. Model the full set of
  expected outcomes in the **success type**, as an enumeration/ADT — not as errors.
  - e.g. `getUser` returning `Option[User]`: "no user for that id" is nominal, encoded
    in the type, not signalled as an error.
- **Error** — an *expected non-nominal* case (the environment or a rule failed in a way
  you anticipated). Reflected in the **error channel** as a `RudderError`. It is a
  **signal** for someone to act on (see "Errors are a signal" below).
- **Defect** — an *out-of-model*, unexpected case: by definition the app is now in an
  unknown state. **Not** reflected in types. The only sound response is to **stop as
  cleanly as possible** — never to pretend it's an error and continue.

Picking the boundary is "choosing your horizon": nothing beyond it is part of your
model, and if it leaks in, the system is inconsistent. Example from Rudder: a JVM
`SecurityException` is an **expected error** inside the JS engine
(`execScript(js): IOResult[String]`, user-supplied scripts) — and a **defect**
everywhere else, by our deliberate choice.

### Handling defects: crash, don't catch

- Don't wrap out-of-model `Throwable`s into the error channel to "keep going".
  `IOResult.attempt` is for **recoverable** exceptions (DB/FS/network) → `SystemError`.
  It is **not** for fatal `Error`s like `OutOfMemoryError`: those are defects that must
  kill the app (a global `Thread.setDefaultUncaughtExceptionHandler` logs and cleans up
  before exit).
- For a ZIO defect that genuinely can't be handled, fail fast (`.orDie`) rather than
  inventing a bogus nominal/error value.

## The base trait (`utils/.../ZioCommons.scala`, `object errors`)

```scala
trait RudderError {
  def msg: String                                   // what went wrong
  def fullMsg: String = getClass.getSimpleName + ": " + msg
}
```

## Built-in errors

- `SystemError(msg, cause: Throwable)` — produced by `IOResult.attempt`; wraps a thrown
  exception with a readable, trimmed stack (only `com.normation` frames).
- `Unexpected(msg)` — "I wasn't expecting that value".
- `Inconsistency(msg)` — a business-logic inconsistency. `Either[String, _].toIO`
  becomes an `Inconsistency`.
- `Chained(hint, cause)` — adds context while preserving the underlying error.
- `Accumulated(NonEmptyList[E])` — many errors at once (e.g. validating a collection);
  has `.deduplicate`.
- `SecurityError` (trait) — a failure caused by a security concern; prints as
  `SecurityError: …` (see [`600`](600-security-in-depth.md)).

## One common super-trait — on purpose

We use a **single** `RudderError` super-trait for the whole app rather than separate,
unrelated error hierarchies per sub-system. This is a deliberate choice:

- it lets the compiler **automatically categorize** results — no boilerplate
  transformers to map between error types at every call;
- shared tooling (`Chained`, `SystemError`, `notOptional`, `chainError`, `accumulate`)
  works on *all* errors, written once;
- in practice `Chained` is enough for trans-system cases, and the rare specific need is
  a cheap ad-hoc combinator.

So: **don't introduce a parallel error hierarchy with no common supertype.** Define
domain subtypes *of* `RudderError`. (If Rudder were split into separately-compiled
services, a shared error lib would be the equivalent.)

## Define a domain error per module

A bounded context defines its own subtype(s) so callers can pattern-match meaningfully:

```scala
sealed trait CampaignError extends RudderError
object CampaignError {
  final case class NotFound(id: CampaignId) extends CampaignError { def msg = s"Campaign '${id.value}' not found" }
  final case class Invalid(msg: String)     extends CampaignError
}
```

Keep it small and specific; don't invent a subtype for every line. Reuse
`Inconsistency`/`Unexpected` for one-off generic cases.

## Errors are a signal — give agency

An error's only purpose is to be **acted on** by whoever has to deal with the problem
(possibly you, at 3am). So a message must be **unambiguous and actionable**, and you
should write it for a specific audience. An application always has **three kinds of
users — don't forget any**:

- **end users** — can influence *inputs*. Help them with precise parameter types and
  messages that say what to give instead (overlaps with parse-don't-validate, `201`).
- **ops** — concerned with the environment and system interactions. This is typically
  what surfaces in the error channel (`SystemError`, connection/FS issues): give them
  enough to fix the environment.
- **developers** — make the model's hypotheses and limits. On a *defect*, dump enough
  context (stack, state) to fix the model.

When an error crosses a sub-system boundary, **translate it** so it stays relevant to
its new audience — add context with `chainError` (each layer contributes a hint) rather
than leaking a raw lower-level cause unexplained.

## Adding context: `chainError`

Wrap an error with a hint as it bubbles up, instead of constructing strings by hand:

```scala
repo.load(id).chainError(s"Error while loading rule '${id.value}'")   // on ZIO and on Either
```

This yields a `Chained` whose `fullMsg` reads `hint; cause was: <cause.fullMsg>`.

## Accumulating errors

When processing many items and you want *all* failures, not the first, use the
accumulation helpers in `object errors`:

```scala
items.accumulate(parseOne)        // Iterable[A] => IOResult[List[B]], errors -> Accumulated
results.accumulate                // Iterable[PureResult[A]] => PureResult[List[A]]
```

This is how "parse, don't validate" surfaces every problem at once
(see [`201`](201-parse-dont-validate.md)).

## Guidance

- **Fail with a value, don't throw.** Throwing is only for the inside of
  `IOResult.attempt`, which converts a *recoverable* exception to `SystemError`.
- **Classify deliberately:** expected outcome → success type (enumeration); expected
  failure → `RudderError`; out-of-model → defect, crash cleanly.
- Give every error a **clear, actionable `msg`** aimed at whoever must react
  (user / ops / dev). Operators read `fullMsg`.
- Add context with `chainError` rather than rebuilding messages.
- Don't over-model: a couple of domain subtypes per context is plenty.
- **Keep good error handling on the path of least resistance.** If it isn't easy and
  boilerplate-free, it won't be done consistently — that's why the combinators in
  `object errors` exist; prefer them (and add small ones) over ad-hoc handling.
