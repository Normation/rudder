# 300 — JavaScript in Lift templates & vendored assets

The only hand-written JS is the glue that mounts an Elm app and wires its ports to browser/DOM
APIs. Keep it small; put logic in Elm.

**Avoid JavaScript as much as possible** — prefer Elm, or check whether the need can be handled
backend-side. The legitimate JS-heavy exceptions are components that are essentially JS-driven
(dashboard graphs, the nodes-table column management, tooltips, PDF/chart glue). And **remove
`console.log()` / `alert()`** before committing. (These are checklist items — see
[`900`](900-pr-review-checklist.md).)

## `let`/`const`, not `var`; ternaries for simple conditionals

Immutable values are `const` — this is on the frontend checklist and shows up in review. Use a
ternary instead of a `var` + reassigning `if`.

```js
$(document).ready(function () {
    const main = document.querySelector("main");
    const path = window.location.pathname.split("/");
    const benchmarkId = path[path.length - 1];

    const parsed = parseFloat(new URLSearchParams(window.location.search).get("threshold"));
    const threshold = isNaN(parsed) ? 85 : parsed;

    const app = Elm.MyApp.init({ node: main, flags: { contextPath, benchmarkId, threshold } });
    app.ports.errorNotification.subscribe(function (str) { createErrorNotification(str); });
});
```

The only legitimate non-`const` in these templates are values genuinely reassigned across
separate `<script>` blocks (e.g. `hasReadRights`/`hasWriteRights`, set to `true` inside
`lift:authz` blocks) — those stay `var`.

## CSP & cached resources

- Every inline `<script>` uses `data-lift="with-nonce"` (CSP nonce). Don't add inline JS without
  it, and don't inline styles that need a nonce — prefer the plugin's `.scss`.
- Asset `<script>`/`<link>` tags use `data-lift="with-cached-resource"` for cache-busting.
- Rudder globals available to template JS: `contextPath`, `createErrorNotification`,
  `createSuccessNotification`, jQuery (`$`), and charting helpers (`doughnutChart`) when
  `charting.js` is included.
- Images are served at `<contextPath>/images/...` (e.g. the logo
  `/images/logo/rudder-logo-rect-black.svg`).

## Move non-trivial JS out of the template

When the port glue grows beyond a few lines (e.g. building a PDF, driving a chart), put it in a
**dedicated served `.js` file** exposing a single global, and call it from the template:

```html
<script src="/toserve/<destDirectory>/benchmark-report-pdf.js" data-lift="with-cached-resource"></script>
...
app.ports.printReport.subscribe(function (data) { buildBenchmarkReportPdf(data); });
```

Wrap such a file in an IIFE and attach one function to `window`; document the expected payload
shape at the top.

## Third-party libraries come from npm

**We don't vendor libraries — we declare them in npm.** Add the dependency to the module's
`package.json` (`rudder-web/src/main/package.json`, or `<plugin>/src/main/package.json`) and let
the build deliver it; don't commit a `.js` under `toserve/`, which is build output. Notes:

- Reuse what Rudder already loads (jQuery, Bootstrap, chart.js, datatables…) before adding a
  dependency — the existing list is in that same `package.json`.
- Prefer reading data you already have (e.g. a chart `<canvas>` via `canvas.toDataURL()`) over
  pulling in a library at all.
- A new dependency must be Apache2/BSD/MIT-compatible (same policy as the rest of the stack),
  and is a supply-chain decision, not a detail: raise it in the PR rather than slipping it in.
