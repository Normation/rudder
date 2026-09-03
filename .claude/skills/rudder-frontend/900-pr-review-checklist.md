# 900 — Front-end PR review checklist

> **Authoritative, always-up-to-date source:** the team's Notion page
> **"Check-list de PR front-end"** —
> <https://app.notion.com/p/rudderio/Check-list-de-PR-front-end-33b8750cc48380398363f7cda258f0b3>
> (internal). **Consult it when reviewing or preparing a front-end PR** — it evolves; the list
> below is a snapshot for offline use and may lag. When they disagree, the Notion page wins.

The essentials to check on any front-end PR. Items called out as **blocking** must be fixed
before merge.

## General

- End every file with a trailing newline (empty line at end of file).
- Resolve `TODO`s and remove the `TODO` comments.

## HTML structure

- **No inline styles** (no `style="..."` attribute on elements) — **blocking**. Use classes.
- Don't use spaces to position text.
- Positioning/spacing: use Bootstrap utility classes (`my-*`, `me-*`, `ps-*`, …) —
  <https://getbootstrap.com/docs/5.3/utilities/spacing/#margin-and-padding>.
- Use Bootstrap classes as much as possible —
  <https://getbootstrap.com/docs/5.3/customize/color/#colors>.
- Colors: when using Bootstrap color classes, make sure the color is part of Rudder's graphic
  charter — <https://docs.rudder.io/devel/9.1/graphic-charter/>.
- Use **`.text-break-spaces`** (Rudder's extension of Bootstrap's `.text-break`) to wrap text.
  Use it whenever displaying code or indentation-sensitive content: it preserves the original
  indentation while wrapping so content doesn't overflow its container.
- Use **`.alert`** rather than `.callout`.
- For instant on/off filtering/toggling, use a **Bootstrap switch**
  (`.form-check.form-switch` + `role="switch"` on the input) rather than a plain checkbox —
  <https://getbootstrap.com/docs/5.3/forms/checks-radios/#switches> (rationale: issue #28444).
- **Avoid Lift tags** (`<lift:xxx> … </lift:xxx>`) as much as possible.
- The layout must be **responsive** — check by resizing the browser window and with the
  Firefox/Chrome dev-tools responsive/device mode.
- **Accessibility:** on every interactive component, set `aria-*` attributes (so the component
  has a description that can be translated to audio) —
  <https://www.digidop.com/fr/blog/aria-label>.
- **Heading levels follow document semantics, not size.** Exactly one `<h1>` per page (the page
  title); section titles under it are `<h2>` (then `<h3>`, …) — don't pick a level for its
  visual size. To keep a given size while using the correct level, use Bootstrap font-size
  classes, e.g. `h2.fs-5` instead of `h5`.

## Sass / SCSS

- Use Sass features as much as possible (nesting, the variable system, …).
- **Never hardcode color codes** (hex, rgb, …) — use the variables from `_rudder-variables.scss`:

  ```scss
  @use 'rudder-variables' as rudder;

  ... {
    color: rudder.$warning;
  }
  ```

## JavaScript

- **Avoid JavaScript as much as possible.** Prefer Elm; or check whether the bug/feature can be
  handled upstream (backend). Exceptions are pages/components that are essentially JS-driven
  (dashboard graphs, the nodes table column management, tooltips, …).
- Remove `console.log()` and `alert()`.
- Use `let`/`const`, **not `var`** (prefer `const` for immutable values).
- Use the global **`contextPath`** to reach an asset (e.g. images) — **never absolute paths**.
  Example: <https://github.com/Normation/rudder/pull/3895>.

## Elm

- General view/model design: the **model is a tree**, and the **view walks down that tree**.
- A `view` function should take **a single parameter: a model**.
- Date format must follow the ADR —
  <https://docs.rudder.io/devel/adr/webapp/27084-date-format-timezone.html>.
- Tooltips: do **not** escape content with `htmlEscape` — Bootstrap does it automatically.
- Remove `Debug.log`.
- Write **unit tests** and **acceptance tests**.

## Design

- **Forms — `description` field:** a plain text `input` (not a textarea, no layout/markup).
- **Forms — `documentation` field:** a `textarea` that supports **Markdown**.
- **Notifications:** three types only — `error`, `warning`, `success`. Always a JS call
  `create<Result>Notification` (e.g. `createErrorNotification`, `createSuccessNotification`).
