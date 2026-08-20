# 000 — Application architecture

## One app per screen

Each Rudder screen is a self-contained Elm app: a `port module` with `Browser.element`
(`init` / `view` / `update` / `subscriptions`). Apps don't share a runtime; they share **source
modules**. The entry file is named after the app (e.g. `SecurityBenchmarks.elm`,
`BenchmarkReports.elm`) and is the only one with `main = Browser.element { ... }`.

```elm
port module BenchmarkReports exposing (..)

main : Program Flags Model Msg
main =
    Browser.element { init = init, view = view, update = update, subscriptions = \_ -> Sub.none }
```

**The gulp build discovers entry points by grepping sources for `Browser.element`** and emits
`rudder-<lowercased-basename>.js` (see [`400`](400-build-format-review.md)). So *adding a new
app is just adding a new `Browser.element` module* — no build-config change. Conversely, don't
put `Browser.element` in a helper module.

## The module set (reuse these)

A typical app is split into small modules, most of them shared across the screens of a plugin:

- **`DataTypes.elm`** — `Model`, `Msg`, and all domain `type`/`type alias`. Dumb data.
- **`JsonDecoder.elm` / `JsonEncoder.elm`** — decoders/encoders for those types.
- **`ApiCalls.elm`** — HTTP requests (`Model -> Cmd Msg`), built on `Http.Detailed`.
- **`ApiError.elm`** — `errorMessage` / `decodeErrorDetails` (see [`200`](200-http-json-ports.md)).
- **`ViewUtils.elm`, `View*.elm`** — rendering helpers and per-tab views.

When a second app needs the same logic, **factor it into one of these modules** instead of
copying. Standalone apps (like a report page) can define their own small `Model`/`Msg` locally
but still import the shared decoders/encoders/`ViewUtils`.

## Flags, `contextPath`, and mounting

The app is mounted into a Lift template that renders `<main></main>` and inits Elm with flags:

```js
const app = Elm.BenchmarkReports.init({ node: main, flags: initValues });
```

- **`flags`** is a record of primitives/records (JSON-encodable). Always include `contextPath`
  and the read/write-rights booleans; add screen-specific values (ids, query params).
- **`contextPath`** is the app's base URL prefix (usually `""`). Build every server/asset URL
  as `model.contextPath ++ "/secure/api/..."` or `++ "/images/..."` — never hardcode a leading
  `/` root. API base is `<contextPath>/secure/api/`.

## Lift snippet integration (the template + the route)

A screen is a **Lift template** in `src/main/resources/template/<name>.html`:

- Root is `<div ... data-lift="surround?with=common-layout;at=content">` so Rudder's
  navbar/sidebar wrap the content; the Elm app mounts into `<main></main>`.
- Assets are declared in `<head_merge>` with `data-lift="with-cached-resource"` (cache-busting)
  and scripts run in `<script data-lift="with-nonce">` blocks (CSP — see
  [`300`](300-javascript-in-templates.md)).

The **route** is registered server-side in the plugin's `*PluginDef.scala` `pluginMenuEntry`
(a `Menu(...) / "secure" / "security" / ... >> Template(() => ClasspathTemplates("template" ::
"<name>" :: Nil) ...)`), often `Hidden` for detail/report pages. See
[`rudder-scala/103`](../rudder-scala/103-rest-api-and-endpoints.md) for the API side.

## Elm↔JS: ports only

The Elm/JS boundary is **ports**, nothing else. Outgoing ports (`X : payload -> Cmd msg`) are
`app.ports.X.subscribe(...)`-d in the template; incoming ports (`X : (payload -> msg) -> Sub
msg`) are `app.ports.X.send(...)`-d from JS. Keep payloads to JSON-encodable records. See
[`200`](200-http-json-ports.md) for HTTP and [`300`](300-javascript-in-templates.md) for the JS
side.
