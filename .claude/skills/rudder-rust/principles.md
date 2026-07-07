# Rust — engineering principles

Mechanics live in [SKILL.md](SKILL.md) and [code.md](code.md); security in
[security.md](security.md). This file is the *why* and the mindset.

## Migration mindset
- Don't mass-rewrite legacy on sight. Apply current conventions to code you create or
  substantially edit; nudge neighbours when it's cheap and safe.
- Bug fixes land on the oldest affected branch and **up-merge** forward → keep diffs
  focused so they merge cleanly.

## Data structures carry the design
- **The data model *is* the design.** Get the types and structures right and the code that
  operates on them tends to fall out simply; a clever algorithm over the wrong structure stays
  complicated forever. Spend your thinking budget on the data first.
- Before writing procedures, pin down what the data *is*: which states exist, what relates to
  what, who owns what, which invariants must hold. Then encode that in the *shape* of the data
  (enums, newtypes, non-optional fields, private fields + guarded constructors) so wrong states
  can't be represented. Most of the rest of this file — signatures, parse-don't-validate,
  newtypes, enums-over-booleans — is this one idea applied.
- When code feels hard to write, **suspect the data structure before the code.** Reshaping the
  types usually deletes more complexity than any amount of cleverness in the functions — and a
  good structure makes the review about *intent*, not mechanics.

## Make signatures tell the truth
- Precise types, not stand-ins: `fn get(id: &NodeId)`, not `fn get(id: &str)`.
- Fallible → `Result`; maybe-absent → `Option`; several outcomes → an `enum`. No hidden
  panics or side effects behind an innocent-looking signature.
- "Longer but naively explicit" beats clever. A reader should see what's required and
  what can happen from the type alone.

## Newtypes over primitives
- A domain concept is a *type*, not `String`/`u32`. `struct NodeId(String)`; hang
  `parse`/`Display`/serde on it. Stops mixing up a `NodeId` with a `RuleId` and anchors
  invariants. If you're about to write `f(x: String, y: String)`, name them first.

## Parse, don't validate
- Untrusted input (HTTP, CLI, config, DB, node reports) is parsed into a typed
  value **at the boundary, once**; inward code assumes validity and never re-checks.
- Use a validating constructor / `FromStr` / `TryFrom` returning `Result`, not a bare
  struct you re-validate later. Make illegal states unrepresentable (private field +
  guarded constructor).

## Enums over booleans
- A `bool` says *nothing* at the call site: `render(template, true, false)` is unreadable and
  easy to transpose. Model a real choice as a small `enum` — `Mode::{Trusted, Untrusted}`,
  `Overwrite::{Yes, No}` — so the type names the meaning and the compiler forces each case.
  Same "illegal states unrepresentable" move as newtypes: two `bool`s allow four states when
  only three are valid; an enum lets only the real ones exist.
- So a `bool` **parameter**, or several co-varying `bool` fields, is a smell → reach for an
  enum. **Returning** a `bool` from a genuine yes/no question is fine (`is_empty()`,
  `is_trusted()`) — the predicate name already carries the meaning.

## Errors: classify deliberately — nominal / error / defect
- **Nominal** = expected outcome (incl. "not found", "rejected") → encode in the return
  type (`Option`, an `enum`). *Not* an error.
- **Error** = expected failure → `Result`. `anyhow` is the cross-cutting channel;
  `thiserror` enum when callers must match. Message is actionable and names the offending
  value; add context with `.context(...)` rather than rebuilding strings.
- **Defect** = out-of-model / "can't happen" → `panic!`/`expect`/`unreachable!`. Crash
  cleanly; don't launder it into a fake error/`Ok` and continue.
- An `unwrap`/`expect` in runtime code asserts a defect is impossible — only for true
  invariants, and `expect` with a reason. In a parser over untrusted bytes, a panic is a
  DoS: return an error.
- Audience: write the message for whoever must act — end user (fix input), ops (fix
  environment), dev (fix the model).

## Less code; rule of three
- Optimise for fewer lines and fewer moving parts, not cleverness or "completing the
  pattern". Delete more than you add — code *and* dependencies.
- Duplicating once is fine, preferred over premature abstraction. A 2nd copy shows what
  actually varies. Consolidate at the **3rd**. Don't build a generic solution for a
  single or imagined use.

## Mutability & imperative style are fine locally
- Rust is not a purely-functional language; don't contort code to avoid `mut`. Local
  mutation and plain loops are idiomatic and often the clearest option — reach for whatever
  reads best, not whatever looks most "functional".
- **Iterator/combinator chains for pure, immutable transformations** (map/filter/fold over
  values, building a new collection) — they're concise and side-effect-free.
- **When the body has side effects** (I/O, logging, mutating external state, early
  `return`/`?` on each item, fallible steps you want to short-circuit clearly), **prefer a
  `for` loop.** A `for_each`/`try_fold` that hides effects and control flow inside a closure
  is harder to read and debug than the equivalent loop — don't force it.
- Keep any `mut` **scoped as tightly as possible** (build a value in a local `let mut`, then
  hand out an immutable binding). Local mutation, narrow lifetime; shared mutable state is
  the thing to avoid, not `mut` itself.

## Cloning is usually fine — don't over-optimise it
- A `.clone()` that simplifies the code or clarifies ownership is a good trade; don't twist
  lifetimes, add `Rc`/`Arc`, or fight the borrow checker to shave an allocation that doesn't
  matter. Most of our data is small and most paths aren't hot, so the clone is invisible —
  optimise ownership only on a measured hot path or for genuinely large data.
- **But treat repeated/awkward cloning as a design signal.** Cloning the same data over and
  over, or a clone that papers over a borrow-checker fight, usually means ownership lives in
  the wrong place — fix the structure (who owns what, pass a reference, split a type, one `Arc`
  at the boundary) rather than sprinkling `.clone()`.

## Comments explain *why*, not *how*
- Capture intent, rationale, a gotcha, an invariant, an issue link — not a restatement of
  what the code already says. Prefer good names / small functions over narration.
- Don't explain the same thing in several places; keep it in one place. Terse and direct.
- **Spend length where it's earned.** A long, detailed comment is for something genuinely
  hard or confusing — a subtle invariant, a non-obvious ordering/concurrency constraint, a
  workaround for an external bug (link it), a "this looks wrong but is deliberate" (like the
  `danger_*` TLS calls in [security.md](security.md)). Straightforward code needs no prose;
  don't pad it. The comment budget tracks the difficulty, not the line count.

## Serialization is a contract
- Anything read outside our code (relay/REST API, on-disk policy, DB JSON, technique
  YAML) is a contract with a slow, back-compat lifecycle; the internal model is not and
  changes freely. Keep them separable.
- Start simple: `derive(Serialize, Deserialize)` on the model directly — **but pin the
  exact serialized form in a test immediately** (the guardrail against silent format
  drift).
- Pin tokens explicitly: `#[serde(rename = "…")]` so renaming a variant/field can't shift
  the wire value; accept old spellings on input with `#[serde(alias = "…")]`.

## Testing is a design tool
- Tests land with the code; untested = unfinished. Hard-to-test is a design smell (too
  coupled, or one unit doing too much) → fix the design (pure logic behind a trait,
  effects pushed out), not heavier test machinery.
- Spend effort on business logic and "broader unit tests" — a slice exercised end-to-end
  with controllable inputs and **no real I/O**, so it stays fast and deterministic. Skip
  trivial getters/data-shuffling. 100% coverage is a non-goal.
- Test doubles = another impl of the trait, injected via constructor/generic — no mocking
  framework. (Runner/assertion mechanics: nextest, pretty_assertions, proptest — see
  [SKILL.md](SKILL.md).)
- Fixed a bug? Add the test that would have caught it — the suite is a regression ledger.

## Dependencies: strict, minimize
- Default posture: **remove**, don't add. Before adding, check an in-tree dep already does
  the job (anyhow/thiserror, serde, tracing, nom, clap/gumdrop, reqwest, …). Justify any
  addition (need, license, maintenance health, size); the best dependency change is one
  that lets us drop something.
- The license/advisory/source gate is `deny.toml` + `cargo deny` — **not** the Scala
  Apache/BSD-only rule. This workspace is GPL-3.0-or-later; `deny.toml` is the source of
  truth. Git deps must be allow-listed there.

## Security is a design constraint
- Integrated from the start, not bolted on: defense in depth, parse at the boundary, least
  privilege, **fail closed**. Rust specifics and the reviewer checklist →
  [security.md](security.md).
