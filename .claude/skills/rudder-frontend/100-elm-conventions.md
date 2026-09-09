# 100 — Elm code conventions

Write idiomatic Elm 0.19. The two mechanical gates ([`400`](400-build-format-review.md)) settle
most style questions; this file covers what they don't.

## Formatting is mechanical — never hand-format

All Elm is `elm-format`-ed. **Run `npm run elm-format-all` before committing** (it runs
`elm-format elm/sources --yes`). A reviewer asking for "elm-format 🙂" means you skipped it.
Don't fight the formatter or reflow code by hand; match whatever it produces.

## Type annotations & modules

- **Annotate every top-level value/function.** `elm-review`'s `NoMissingTypeExpose` and the
  house style expect explicit signatures at module boundaries.
- **Small, single-purpose modules.** Keep `Model`/`Msg`/domain types in `DataTypes`, decoders in
  `JsonDecoder`, etc. (see [`000`](000-app-architecture.md)). A new cross-cutting helper gets its
  own module (e.g. `ApiError`) rather than being wedged into an app or duplicated.
- **Watch for name clashes when adding shared types.** The module exposes a lot (`exposing
  (..)`), so a new `type alias Foo` can collide with an existing constructor. If it does,
  rename the new one (e.g. `SectionReport` instead of a clashing `SectionScore`).

## Reuse over re-implementation

[Principle 4 — don't repeat yourself past twice](../rudder-principles/SKILL.md#4-dont-repeat-yourself-past-twice),
in a codebase of one app per screen where the same concern recurs in every app.

Before writing a helper, check whether it already exists in `ViewUtils`, `JsonDecoder`,
`ApiError`, etc. If two apps need the same logic, **extract a shared module** and have both
import it — don't copy. Concrete example: error handling was duplicated in a report app and the
main app; the fix was a shared `ApiError.errorMessage`, with each app keeping only a thin
`processApiError` that wires its own `Model`/ports (see [`200`](200-http-json-ports.md)).

Note that the strict case of principle 4 applies here too: a **display rule** that encodes a
business decision (a compliance colour threshold, what counts as "applied", how a score maps
to a letter) is business logic. Duplicated across two apps, it drifts, and the same node ends
up shown differently on two screens. Put it in a shared module the first time you copy it —
and if the rule is really the server's, get it from the API instead of re-deriving it.

Keep sibling apps **consistent**: same helper, same message shape, same naming. A review that
touches one app's error/formatting path usually implies aligning the others too.

## Model / Msg / update

- `Model` is immutable; update returns `( Model, Cmd Msg )`. Derive UI state, don't cache what
  you can compute in `view`.
- One `Msg` constructor per event; decode incoming port/JSON values inside `update`/subscriptions
  and fold failures into a neutral `Ignore`/error branch rather than crashing.
- No `Debug.log` / `Debug.todo` in committed code (`elm-review` rejects them).
- Prefer `case`/pattern matching and small pure helpers over deeply nested `let`.

## View & model shape (checklist rules)

These come from the front-end PR checklist ([`900`](900-pr-review-checklist.md)); keep to them:

- **The model is a tree; the `view` walks down that tree.** Structure `view` as a recursion over
  the model rather than reaching across it.
- **A `view` function takes a single parameter: a model** (the relevant sub-model). Don't thread
  many loose args through view functions.
- **Dates** follow ADR 27084 (format + explicit timezone) —
  <https://docs.rudder.io/devel/adr/webapp/27084-date-format-timezone.html>.
- **Tooltips:** don't escape content with `htmlEscape` — Bootstrap escapes it automatically.
- Add **unit tests** and **acceptance tests** for new UI logic.
- **Styling:** no inline styles; use Bootstrap utility classes and Rudder's SCSS variables
  (`_rudder-variables.scss`), never hardcoded colors — details in [`900`](900-pr-review-checklist.md).
