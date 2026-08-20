---
name: rudder-frontend
description: >-
  Conventions for Rudder's web frontend: Elm applications (the UI logic) and the thin
  JavaScript/Lift-template glue around them. Use whenever reading, writing, reviewing, or
  refactoring Elm under any `src/main/elm/sources`, the JS in `src/main/resources/template/*.html`
  or vendored `toserve` scripts, or the elm/gulp build. Applies to the `rudder` webapp and to
  the `rudder-plugins` / `rudder-plugins-private` plugin UIs alike (same Elm 0.19 + gulp stack).
  Covers app architecture (Model/Msg/update/view + ports), Lift snippet integration, HTTP/JSON,
  error handling, shared-module reuse, JS-in-template rules, and the elm-format / elm-review /
  gulp workflow.
---

# Writing Rudder's frontend (Elm + JS)

Rudder's UI is a set of **Elm 0.19 single-page apps**, one per screen, each compiled to a
standalone JS bundle and mounted into a **Lift HTML template** that provides the surrounding
Rudder chrome. The only JavaScript we write by hand is the thin glue in those templates (and a
few vendored libraries) that wires Elm **ports** to browser/DOM APIs. Keep logic in Elm; keep
JS minimal, `const`, and out of the templates when it grows.

**Scope — all Rudder frontends.** These conventions hold for the Elm/JS in `rudder`
(`webapp/sources/rudder/rudder-web/src/main/elm`) **and** in the plugin repos
(`rudder-plugins`, `rudder-plugins-private`, under each module's `src/main/elm`). They share the
same toolchain (Elm 0.19.1, `elm-format`, `elm-review`, gulp) and the same app shape. The
server side these apps talk to follows [`rudder-scala`](../rudder-scala/SKILL.md) — read it for
the API/JSON contract you're decoding.

## Read the shared principles first

The **cross-language engineering principles** — which apply to every Rudder codebase, not
just the frontend — live in **[`rudder-principles/SKILL.md`](../rudder-principles/SKILL.md)**.
Read that first, and keep it applied for the whole task: the data model is the design, parse
at the edge (pure core), fix the **root cause** rather than the visible symptom, don't repeat
yourself past twice, signatures that tell the truth, name domain concepts as types, weigh
**hot-path cost while planning**, less code, nominal/error/defect, comments, tests as a
design tool, security as a design constraint, and the up-merge rules.

Those principles are *not* restated here. This skill is their **Elm/JS expression** plus the
toolchain mechanics. Elm makes several of them structural rather than optional:

| Principle | Frontend form |
|---|---|
| Parse at the edge, pure core | **decoders and port payloads *are* the edge** — [`200`](200-http-json-ports.md) |
| Data model is the design | `Model`/`Msg`/`DataTypes`; the model is a tree the `view` walks — [`000`](000-app-architecture.md), [`100`](100-elm-conventions.md) |
| Don't repeat yourself past twice | shared modules (`ApiError`, `ViewUtils`, …) — [`100`](100-elm-conventions.md) |
| Nominal / error / defect | a decode failure is nominal (fold into `Ignore`), an API error is an error — never a crash — [`200`](200-http-json-ports.md) |
| Hot-path cost | rarely applies: derive UI state in `view`, don't cache — [`100`](100-elm-conventions.md) |
| Security as a design constraint | never surface a raw API body; CSP nonces, no inline scripts — [`200`](200-http-json-ports.md), [`300`](300-javascript-in-templates.md) |
| Tests as a design tool | unit + acceptance tests for new UI logic — [`900`](900-pr-review-checklist.md) |

Note that Elm has no `null`, no exceptions and no mutation: the "parse at the edge" and
"total functions" principles are enforced by the compiler. What is *not* enforced is what
you do with a `Result` — see [`200`](200-http-json-ports.md).

## Golden rules — frontend-specific (always apply)

1. **`elm-format` is not optional.** Every Elm file is formatted with `elm-format`; CI checks
   it. Run `npm run elm-format-all` (from the module's frontend dir) before committing — do not
   hand-format. See [`400`](400-build-format-review.md).
2. **Keep `elm-review` clean.** No `Debug.log`/`Debug.todo`, no unused deps, exposed types keep
   their needed constructors. See [`400`](400-build-format-review.md).
3. **Shared concerns live in shared modules** (`DataTypes`, `JsonDecoder`, `JsonEncoder`,
   `ApiCalls`, `ApiError`, `ViewUtils`) — the concrete form of "don't repeat yourself past
   twice" here, because sibling apps drift otherwise. Factor a helper into a module rather
   than copying it into a second app. See [`100`](100-elm-conventions.md).
4. **Never show a raw API body to the user.** API errors go through
   `ApiError.errorMessage` / `processApiError`, which decode the `errorDetails` field into a
   readable message — not the raw JSON. See [`200`](200-http-json-ports.md).
5. **Logic in Elm, glue in JS.** JS in a template only wires ports to DOM/browser APIs. Use
   `const` for immutable values, ternaries for simple conditionals, and move any non-trivial JS
   into a dedicated served `.js` file. See [`300`](300-javascript-in-templates.md).
6. **Ports are the only Elm↔JS boundary.** Typed, named, subscribed in the template's nonce'd
   `<script>`. No `Debug`, no side effects outside `Cmd`/ports. See [`200`](200-http-json-ports.md).
7. **Type-annotate top-level values** and keep modules small and single-purpose. Idiomatic Elm:
   pure functions, explicit `Msg`, immutable `Model`.
8. **Run the PR review checklist.** Before opening/reviewing a front-end PR, go through
   [`900`](900-pr-review-checklist.md) — and consult the live Notion checklist it links, which is
   the authoritative, evolving source (no inline styles, Bootstrap utilities + graphic-charter
   colors, SCSS variables, accessibility, responsive, notifications, …).

## How to use this skill

Before substantial work in an area, read the matching topic file (`NNN-topic.md`; the first
digit is the subject area).

| 1st digit | Subject area |
|-----------|--------------|
| 0 | Application architecture — Model/Msg/update/view, ports, Lift snippet integration |
| 1 | Elm code conventions — modules, reuse, naming, formatting |
| 2 | HTTP, JSON decoding, ports, error handling |
| 3 | JavaScript in Lift templates and vendored assets |
| 4 | Build, format, review workflow (elm-format / elm-review / gulp) |
| 9 | Front-end PR review checklist (HTML/SCSS/JS/Elm/design; links the live Notion checklist) |

### Topic index

- [`000-app-architecture.md`](000-app-architecture.md) — one app per screen, `Browser.element`,
  the module set, ports, and how an app is mounted in a Lift template + registered as a route.
- [`100-elm-conventions.md`](100-elm-conventions.md) — formatting, type annotations, shared-module
  reuse, factoring helpers, keeping apps consistent.
- [`200-http-json-ports.md`](200-http-json-ports.md) — `Http.Detailed`, `at ["data"]` decoding,
  mirroring the zio-json contract, ports, and the `ApiError` error-handling pattern.
- [`300-javascript-in-templates.md`](300-javascript-in-templates.md) — `const` over `var`,
  ternaries, CSP nonces, `contextPath`, moving JS into dedicated files, vendoring libs under
  `toserve`.
- [`400-build-format-review.md`](400-build-format-review.md) — `npm run elm-format-all`,
  `elm-format-check`, `elm-review`, and how gulp compiles/serves the apps.
- [`900-pr-review-checklist.md`](900-pr-review-checklist.md) — the front-end PR checklist
  (general / HTML / SCSS / JS / Elm / design), and the link to the live Notion source.

## Sources of truth

- The **shared engineering principles** are in
  [`rudder-principles/SKILL.md`](../rudder-principles/SKILL.md) — read them first; they are
  not restated here.
- The **front-end PR review checklist** on Notion (internal) is the authoritative, evolving list
  of review rules — **consult it for every front-end PR**. Snapshot + link in
  [`900`](900-pr-review-checklist.md).
- **`elm-format` and `elm-review`** are the mechanical arbiters of style — when in doubt, run
  them. The enabled review rules live in `elm/review/src/ReviewConfig.elm`; the format/build
  scripts in the module's `package.json`.
- The **server-side JSON/API contract** these apps decode is defined by
  [`rudder-scala`](../rudder-scala/SKILL.md) (zio-json, `EndpointSchema`). Decoders must match
  it (e.g. `None` → absent field, `Float` serialized as `45.0`).
