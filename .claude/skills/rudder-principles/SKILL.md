---
name: rudder-principles
description: >-
  The engineering principles that always apply when writing, reviewing or refactoring any
  code in Rudder, in any language (Scala, Elm/JS, Rust). Read this FIRST, before the
  language-specific skill, and keep it applied for the whole task — including while
  planning and while reporting what you did. Covers: the data model is the design; parse
  at the edge / pure core; fix the root cause, not the visible symptom; don't repeat
  yourself past twice; signatures that tell the truth; naming domain concepts as types
  (and proposing zero-cost abstractions); assessing hot-path cost when planning; less
  code; the nominal/error/defect classification; comments; tests as a design tool;
  security as a design constraint.
---

# Rudder engineering principles

**These apply to every language and every task.** They are the shared *why* behind the
language skills — [`rudder-scala`](../rudder-scala/SKILL.md),
[`rudder-frontend`](../rudder-frontend/SKILL.md) (Elm/JS),
[`rudder-rust`](../rudder-rust/SKILL.md) — which hold the *mechanics* for each stack. Read
this file first; then the language skill for how to express these rules there.

Nothing here is tool-specific: this is plain Markdown for any contributor or coding agent.

Rules 2, 3, 4, 6 and 7 are the ones most often dropped under pressure. They are also the
ones with a **process** obligation, not just a code obligation: they change what you put
in a *plan* and in a *report*, not only what you put in a file.

---

## 1. The data model *is* the design

Get the types and structures right and the code that operates on them falls out simply. A
clever algorithm over the wrong structure stays complicated forever. **Spend your thinking
budget on the data first.**

Before writing procedures, pin down what the data *is*: which states exist, what relates
to what, who owns what, which invariants must hold. Then encode that in the *shape* of the
data — sum types for closed sets, wrappers for identifiers, non-optional fields, guarded
constructors — so **wrong states cannot be represented**.

**When code feels hard to write, suspect the data structure before the code.** Reshaping
types deletes more complexity than any amount of cleverness in the functions. Most of the
rules below are this one idea applied.

## 2. Parse at the edge; the core is pure structure

Every value crossing an I/O boundary — HTTP request, REST response, CLI arg, config file,
git content, LDAP, DB row, agent report, port payload — is **parsed once, at that
boundary, into a typed value**. Inward code then works only on data that is *correct by
construction* and never re-checks it.

The point is not merely "validate early". The point is that **business logic operates on a
pure, already-trustworthy structure**: no raw strings, no re-parsing, no defensive
re-checks, no I/O in the middle of a rule. If a business function has to ask "is this
string well-formed?", the parsing happened too late.

- The boundary returns a **typed value or an error** — it does not throw, and it does not
  hand back a half-built object.
- **Absence and failure are different.** "Legitimately not there" is an optional;
  "the boundary broke its contract" is an error. Don't collapse the second into the first.
- **Nullability is part of this.** A `null` from foreign/legacy code is handled *at the
  edge*, visibly, once — never sprinkled through the domain.
- Handle nullability and malformed input **visibly** at that edge, so a later reader can
  see the case was deliberately dealt with and *why* the resulting type is optional.
- When parsing a collection of inputs, **accumulate** the errors so the user sees every
  problem at once, not just the first.

Mechanics: [`rudder-scala/201`](../rudder-scala/201-parse-dont-validate.md) and
[`rudder-scala/000`](../rudder-scala/000-coding-philosophy.md) (nulls at the edge);
[`rudder-frontend/200`](../rudder-frontend/200-http-json-ports.md) (JSON decoders and port
payloads *are* the edge); [`rudder-rust/principles.md`](../rudder-rust/principles.md).

## 3. Fix the root cause, not the visible symptom

When you find a bug, **do not stop at the observable consequence.** Trace it back until
you reach the decision that made it possible, and address *that*.

The test is: after your change, is the design **clean, and as simple as the domain's
complexity allows** — or did you add a guard that makes the symptom disappear while the
underlying shape stays wrong? A patch that suppresses a symptom leaves the same defect
free to resurface somewhere you have not looked yet, and adds a line of code whose reason
to exist is invisible.

Typical symptom-level patches, and what they usually indicate:

| You are about to write… | The root cause is usually… |
|---|---|
| a defensive re-check deep in the domain | parsing happened too late (rule 2) |
| a special case for one caller | the signature or the type is wrong (rules 1, 5) |
| the same fix in a third place | duplicated business logic (rule 4) |
| a null/empty guard before a computation | an optional that should never have been optional (rule 1) |
| a re-sort / re-dedup / re-filter "just in case" | an invariant not carried by the type (rule 1) |

**This is a process obligation, not only a coding one.** When the root cause is larger
than the reported bug, or riskier to change (a public contract, a hot path, an old release
branch), you still:

1. state the root cause explicitly,
2. propose the change that removes it,
3. say what it costs and what it touches,

and let the developer decide. Silently shipping only the local patch — without naming the
real cause — is the failure mode to avoid. Fixing the symptom *because the developer chose
that trade-off* is a legitimate outcome; fixing it because you never looked further is
not.

Corollary on scope: fixing a root cause is not licence to rewrite everything around it.
Keep the change proportionate (rules 8 and the up-merge note below).

## 4. Don't repeat yourself past twice

- **Once is fine** — and *preferred* over prematurely adding a parameter, an abstraction
  or a shared helper. Concrete and simple first.
- A **second** occurrence is informative, not alarming: it reveals what actually varies
  versus what is genuinely common. You usually cannot see the right abstraction until you
  have seen it twice.
- At the **third**, stop and consolidate into a single point. The abstraction is now
  informed by real cases instead of guessed up front.
- Corollary: **do not** build a generic or parameterized solution for a single, or
  imagined, use. Over-engineering to avoid duplication is worse than the duplication.

**Business logic is the strict case.** The tolerance above is about *boilerplate*. A
repeated piece of business logic — a rule, an authorization or ownership check, a
computation, a filter that enforces visibility — must **not** be copy-pasted a third time,
and often not a second. Consolidate it into **one** named function over exactly the inputs
it needs, so the rule has a single source of truth.

Duplicated boilerplate is noise. Duplicated business logic **drifts**, and produces
inconsistent behaviour between the places that were supposed to agree — a correctness and
security problem, not a tidiness problem.

## 5. Signatures tell the truth (WYSIWYG)

A signature should state the whole truth — what it needs, what it can produce — with no
hidden surprises.

- **Structure the inputs.** Take the precise type, not a stand-in (rule 6).
- **Enumerate the outputs.** Every expected outcome lives in the result type: an optional
  for "maybe absent", a sum type for several cases. Not hidden behind an exception.
- **No hidden constraints, dependencies or side effects.** If it does I/O or can fail, the
  type says so.
- **Total functions** where you can: defined for every value of their input types; push
  partiality into the types (parsed inputs, explicit result types).

The house idiom is "**longer but naively explicit**" over clever. A reader should see what
is required and what may happen from the type alone.

Mechanics: [`rudder-scala/000`](../rudder-scala/000-coding-philosophy.md),
[`rudder-scala/300`](../rudder-scala/300-effects-zio-ioresult.md),
[`rudder-rust/principles.md`](../rudder-rust/principles.md).

## 6. Name domain concepts as types — and propose the zero-cost form

A domain concept is a **type**, not a primitive. Not a bare string for an id, a name, a
token, a path; not a bare integer for a count that has a unit; not a map of strings
standing in for a record. Typed concepts make signatures self-documenting, stop you mixing
up two identifiers that are both "a string", and give invariants somewhere to live.

**No bare boolean for a domain concept.** A boolean parameter says nothing at the call
site — `f(true, false)` is unreadable and easy to transpose — and it can never grow a
third case. Model the concept as a small named sum type instead. Two booleans allow four
states when only three are valid; a sum type lets only the real ones exist. *Returning* a
boolean from a genuine yes/no question is fine: the predicate's name carries the meaning.

### Propose the zero-cost abstraction; let the developer weigh it

Several of these wrappers are **zero-cost**: they exist at compile time and vanish at
runtime — Scala 3 `opaque type` is the canonical example, Rust newtypes likewise. When
such a form exists for the case at hand, **propose it explicitly rather than silently
keeping the primitive.** Say, in one line each:

- **what it buys** — intent visible at the call site, impossible confusion between two
  same-shaped concepts, one place to hold the invariant;
- **what it costs at runtime** — for an `opaque type` or a Rust newtype: *nothing*; for a
  boxing wrapper, an allocation per value, which is only relevant if rule 7 applies;
- **what it costs in ceremony** — the accessor/extension boilerplate, and any friction at
  the serialization boundary.

Then let the developer decide the trade-off between **clarity of intent and performance**.
That decision is theirs; surfacing the option is yours. "It would have been nicer as an
opaque type" is not something to leave unsaid, and not something to impose either.

Preference order for a concept backed by a **single** value: zero-cost newtype (Scala
`opaque type`) > boxing value wrapper > raw primitive (never, for a domain concept). For a
concept that genuinely *is* several values, use a record — not a newtype over a tuple.

Mechanics: [`rudder-scala/001`](../rudder-scala/001-scala3-idioms.md),
[`rudder-scala/400`](../rudder-scala/400-domain-case-classes.md),
[`rudder-rust/principles.md`](../rudder-rust/principles.md).

## 7. Weigh hot-path cost — while planning, not after

Rudder is infrastructure software: some code runs once per HTTP request, and some runs
once per node × rule × directive × report, on installations with tens of thousands of
nodes. The difference is several orders of magnitude, and it is **not visible in the code
you are editing.**

So, **as part of planning a change — before writing it — state whether it lands on a hot
path, and which one.** Known hot paths include policy generation, compliance computation
and aggregation, agent-report ingestion, node-fact updates, and any per-node or per-rule
loop over the full inventory. Everything else — configuration screens, one-shot REST
endpoints, bootstrap, migrations — is cold, and clarity wins there with no discussion.

For a hot path, name the cost in the plan: allocation per element, boxing, an extra
traversal, a map lookup inside a loop that could be hoisted, an `O(n²)` join over node
sets, an I/O or LDAP call that just moved inside an iteration.

Then apply the trade-off honestly, in this order:

1. **Cold path → clarity wins.** Do not pre-optimize. An allocation nobody measures is
   not a cost.
2. **Hot path → say so, and quantify if you can.** A local, well-scoped optimization is
   legitimate here; the *API stays clean* even when the implementation is not.
3. **Never trade correctness or security for speed** — a skipped check is not an
   optimization.
4. **Measure before believing.** A benchmark beats an intuition, including yours. Do not
   claim a speed-up you have not measured.

The reciprocal also holds: **do not use "it might be hot" as an excuse** to reach for a
mutable, untyped or duplicated design in code that runs once per page load. Vague
performance anxiety is not an argument; a named hot path with a named cost is.

Mechanics — the local-mutability carve-outs and their limits:
[`rudder-scala/000`](../rudder-scala/000-coding-philosophy.md),
[`rudder-rust/principles.md`](../rudder-rust/principles.md).

## 8. Less code is better code

- Optimize for **fewer lines and fewer moving parts**, not for cleverness or for
  "completing the pattern". A 5-line solution a teammate grasps at a glance beats a
  50-line architecturally pure one.
- Before adding a class, a layer, or an abstraction, ask whether the code is simpler
  without it. We are attached but **not integrist** about DDD/hexagonal.
- **Delete more than you add** — code *and* dependencies. Default posture on a new
  dependency is *no*; the best dependency change is the one that lets us drop something.
- **No type-level acrobatics.** Use the power of the language, but get it from
  well-chosen *libraries* that already paid the complexity cost — not from hand-rolled
  type-level machinery in business code. If you are fighting the type system, step back
  and take the boring solution.

## 9. Classify outcomes deliberately: nominal / error / defect

Three different things, three different mechanisms. Conflating them is how error handling
rots.

- **Nominal** — an expected outcome, including "not found" and "rejected". Encode it in
  the **return type** (an optional, a sum type). It is *not* an error.
- **Error** — an expected *failure*: the operation could not be performed for a reason
  the caller may act on. Encode it in the **result type**. The message must be actionable
  and name the offending value.
- **Defect** — out-of-model, "cannot happen", an invariant broken. Fail loudly and
  cleanly. Do **not** launder it into a fake error or a success and carry on.

Write the message for **whoever must act**: the end user (fix your input), ops (fix the
environment), or a developer (fix the model). Errors are a *signal*, not noise to be
swallowed — and never an empty catch.

Mechanics: [`rudder-scala/301`](../rudder-scala/301-error-model.md),
[`rudder-frontend/200`](../rudder-frontend/200-http-json-ports.md) (never surface a raw
API body to a user), [`rudder-rust/principles.md`](../rudder-rust/principles.md).

Background: the error-management philosophy behind this classification comes from the talk
*"Systematic error management in application"* (DevoxxFR 2021, F. Armand).

## 10. Comments explain *why*, not *how*

- Capture **intent, rationale, trade-off**, a genuine gotcha, an invariant, a link to
  context (an issue, an ADR). Not a restatement of what the code already says.
- Prefer making the code self-explanatory — good names, small functions — over adding a
  comment. Reach for a comment when the reasoning cannot live in the code.
- **Spend length where it is earned.** A long comment is for something genuinely hard: a
  subtle invariant, a non-obvious ordering or concurrency constraint, a workaround for an
  external bug (link it), a "this looks wrong but is deliberate". Straightforward code
  needs no prose. The comment budget tracks difficulty, not line count.
- Don't explain the same thing in several places — that is rule 4 applied to prose.

```scala
// good — the why, plus the consequence and a traceable reference
// system object must ALWAYS be ENABLED, otherwise policy generation skips it (RUDDER-1234)
def isEnabled: Boolean = _isEnabled || policyTypes.isSystem

// bad — restates what the code already says
// return true if enabled or if it is a system type
def isEnabled: Boolean = _isEnabled || policyTypes.isSystem
```

## 11. Tests are mandatory, and they are a design tool

- **Code lands with tests.** Untested code is treated as unfinished.
- **Hard to test is a design smell** — too coupled to persistence, user input or
  infrastructure, or one unit doing too many things. Fix it by *fixing the design* (pure
  logic behind an interface, effects pushed outward), not by reaching for heavier test
  machinery.
- A test **materializes the goal** (writing expected input → output clarifies what you are
  actually solving), **documents intent** in a form that cannot silently drift, and
  **tightens the feedback loop** far below "redeploy and click".
- **The suite is a regression ledger.** Fixed a bug? Add the test that would have caught
  it — and, per rule 3, test the *root cause*, not just the symptom you reproduced.
- Concentrate effort on **business logic** and on **broader unit tests**: a slice
  exercised end-to-end with *controllable* inputs and **no real I/O**, so it stays fast,
  deterministic and automated. Skip tests that only assert the framework or the compiler.
- **100% coverage is a non-goal**, and we do not do test-first-always TDD. Write tests
  alongside the code, driven by the design.
- Test doubles are another implementation of the interface, injected — not a mocking
  framework.

Mechanics: [`rudder-scala/900`](../rudder-scala/900-testing.md),
[`rudder-rust/principles.md`](../rudder-rust/principles.md).

## 12. Security is a design constraint

Rudder is a security product managing its users' infrastructure. Security is integrated
from the start, not bolted on afterwards:

- **Defense in depth** — one check is not enough; assume any single layer can be bypassed.
- **Parse untrusted input at the boundary** (rule 2) — that *is* the first security
  control.
- **Least privilege**, and **fail closed**: on doubt, on error, on a missing
  authorization, deny. A failure must never widen access.
- **Never trade a check for performance or convenience** (rule 7), and never weaken an
  existing mitigation to make something work.

Mechanics: [`rudder-scala/600`](../rudder-scala/600-security-in-depth.md),
[`601`](../rudder-scala/601-web-and-output-security.md),
[`602`](../rudder-scala/602-authentication-and-authorization.md),
[`rudder-rust/security.md`](../rudder-rust/security.md).

---

## Working in a long-lived codebase

The codebase spans 15+ years, and several release branches are maintained at once.

- **Don't mass-rewrite legacy on sight.** Apply current conventions to every file you
  create or substantially edit, and nudge neighbouring code when it is cheap and safe.
  Leave every file you touch a little better.
- **Bug fixes land on the oldest affected branch and up-merge forward.** Keep the diff
  focused so it merges cleanly; avoid gratuitously reshaping surrounding code that has
  already moved on in newer branches.
- **But clean code wins.** That constraint *tempers* incidental churn; it does **not**
  justify writing worse code. We will not add convoluted compatibility shims, pick worse
  names, or duplicate logic to dodge a merge conflict — resolve the conflict at merge time
  instead.
- Net: **minimize incidental diff, never compromise the quality of the change itself.**

## Applying these while working

A short self-check before you call a task done — these map to the rules above:

- Did I parse untrusted input at the edge, so the business logic sees only pure, valid
  structure? (2)
- Did I identify the **root cause**, and either fix it or state it explicitly with a
  proposal? (3)
- Is any business rule now living in more than one place? (4)
- Is there a bare string/integer/boolean standing for a domain concept — and if a
  zero-cost typed form exists, did I **propose** it with its clarity-versus-performance
  trade-off? (6)
- Did my **plan** say whether this is a hot path, and name the cost if it is? (7)
- Does every signature I wrote tell the whole truth? (5)
- Are the new outcomes classified as nominal, error or defect on purpose? (9)
- Are there tests, and do they cover the root cause? (11)
- Did I widen access, or leave a failure path that fails open? (12)
