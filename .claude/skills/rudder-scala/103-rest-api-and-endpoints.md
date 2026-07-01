# 103 — REST API & endpoints

REST endpoints are **declared declaratively** as data, separately from their handlers.
Every endpoint carries its HTTP method, path, API version, and — critically — the
**authorization** it requires. This is security-in-depth at the API edge
(see [`600`](600-security-in-depth.md)).

## Endpoint definitions (the schema)

Endpoints are enumerated as enumeratum `EndpointSchema` enums (see
[`401`](401-json-zio-json.md#enums) for enumeratum). Example
(`rudder-rest/.../EndpointsDefinition.scala`):

```scala
sealed trait CampaignApi extends EnumEntry with EndpointSchema with GeneralApi with SortIndex
object CampaignApi extends Enum[CampaignApi] with ApiModuleProvider[CampaignApi] {

  case object GetCampaigns extends CampaignApi with ZeroParam with StartsAtVersion16 with SortIndex {
    val z: Int = implicitly[Line].value
    val description    = "Get all campaigns"
    val (action, path) = GET / "campaigns"
    val dataContainer: Some[String]            = Some("campaigns")
    val authz:         List[AuthorizationType] = AuthorizationType.Configuration.Read :: Nil
  }
  case object SaveCampaign extends CampaignApi with ZeroParam with StartsAtVersion16 with SortIndex {
    val (action, path) = POST / "campaigns"
    val authz:         List[AuthorizationType] = AuthorizationType.Configuration.Write :: Nil
    // ...
  }
}
```

Each endpoint declares:

- **method + path** via the DSL: `GET / "campaigns" / "events" / "{id}"`.
- **API version** it appears in: `StartsAtVersionN` (versioning is explicit, never
  silent).
- **`authz: List[AuthorizationType]`** — the permission(s) required. *Every* endpoint
  must declare this; a read endpoint gets `...Read`, a mutating one `...Write`. This is
  the single source of truth the framework enforces — do not rely on the handler to
  re-check by hand.
- a `description` (feeds API docs) and `dataContainer` (the JSON field results sit in).

## Handlers (the lift adapter)

Handlers live under `rudder-rest/.../rest/lift/` and implement `process(...)` /
`process0(...)`, which receive the parsed `ApiVersion`, `ApiPath`, request, params and
the `AuthzToken`. The body works in `IOResult` and is rendered with the
`.toLiftResponse*` boundary (a `.toBox`-style run, see [`302`](302-bridging-toio-runnow.md)):

```scala
def process(version: ApiVersion, path: ApiPath, resources: String, req: Req,
            params: DefaultParams, authz: AuthzToken): LiftResponse = {
  campaignEventRepository
    .get(CampaignEventId(resources))                 // IOResult[...]
    .toLiftResponseOne(params, schema, _ => Some(resources))
}
```

- `.toLiftResponseOne(...)` / `.toLiftResponseList(...)` turn an `IOResult[A]` into the
  HTTP response, mapping `RudderError` to the proper status/body. Keep the *whole*
  handler in `IOResult` and convert once, at the end.
- Parse path/query/body into typed values first (**parse, don't validate**,
  [`201`](201-parse-dont-validate.md)); never thread raw strings into the service.
- The response JSON is a **contract**: lock it with a test, and once the business model
  needs to diverge from it, serialize a DTO (chimney) rather than the domain object —
  see [`404`](404-serialization-contracts.md).

## Plugin APIs & dynamic status (ADR 28612)

Whether a plugin's API is currently enabled (license/status) is checked **centrally by
the framework**, not by hand in each endpoint. Source of truth: ADR
[`28612-dynamic-checking-of-plugin-status-in-menu-and-api`](../../../adr/webapp/28612-dynamic-checking-of-plugin-status-in-menu-and-api.md).

- A plugin's API provider extends `PluginLiftApiModuleProvider[API]` and takes the
  status as a context parameter: `class MyApiImpl(...)(using status: PluginStatus)`. The
  `given PluginStatus = pluginStatusService` is provided in the plugin's `Conf` object
  (and `AlwaysEnabledPluginStatus` in tests).
- `ApiModule.isEnabled` defaults correctly; only `override def isEnabled = true` locally
  for an endpoint that must always work. Core (non-plugin) endpoints are always enabled.
- Menu entries use `testMenuAccess(<authz>)` (not the bare `TestAccess`) so they
  appear/disappear with the plugin status.
- Don't re-implement plugin-status checks ad hoc in handlers or menus.

## Rules

- Add an endpoint → add a `case object` to the relevant `*Api` schema with method,
  path, `StartsAtVersion*`, `description`, `dataContainer`, **and `authz`**.
- New internal-only endpoints go under `rest/internal/`; public ones under `rest/lift/`.
- Handler returns `IOResult`, converted with `.toLiftResponse*` — no bare `.runNow`.
- Don't bypass the schema/authz mechanism with ad-hoc permission checks.
