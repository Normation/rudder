# rudder-mcp architecture

Prototype MCP server for Rudder. This file keeps a short summary of the design decisions;
update it whenever one changes.

## Design decisions

| Topic | Decision | Why |
|---|---|---|
| SDK | [`rmcp`](https://crates.io/crates/rmcp) (official Rust SDK) | Same language as the rest of `policies/` and `relay/` |
| Transport | Streamable HTTP, served by `axum` on `/mcp` | Runs as a daemon next to the Rudder server, reachable by remote clients |
| Protocol | **Stateless only**: MCP `2026-07-28` (SEP-2567) | No sessions to store or expire; any instance can answer any request |
| Responses | Plain JSON (`json_response`), no SSE, `GET` → 405 | No long-lived streams, so shutdown is quick and proxying is simple |
| Request checks | Per-request protocol metadata required | Invalid requests are rejected before reaching a handler |
| State | Nothing kept between requests: a handler is built per request | Shared data (built at startup) sits behind an `Arc` in the factory closure |
| Read/write split | Separate `read_router` / `write_router`; write tools only exist with `server.read_only = false` (default `true`) | Read-only by default; write tools are neither listed nor callable in read-only mode |
| Tool annotations | Every tool sets `read_only_hint` (and `destructive_hint` for writes) | Lets clients choose when to ask for confirmation. Hints only, not enforcement |
| Access control | Caller's Rudder API token forwarded: `Authorization: Bearer <token>` in, `X-API-Token` out | Rudder's ACLs are the real security boundary; the MCP server holds no credentials |
| Auth check | axum middleware on **every** request (local tools, `tools/list`, `server/discover` included): no/malformed bearer → 401; token checked with Rudder (`GET /apiaccounts/token`): 2xx with an account → accepted, 401 → 401, 403 (token cannot read its own account) → 403, unreachable/2xx without account/other → 502 (fail closed). Refusals logged with their reason | Only callers Rudder knows **and identifies** can use the server. `acl` accounts need `GET apiaccounts/token` in their list |
| Caller identity | The token check response is parsed into `ApiAccount` (id, name, `ro`/`rw`/`acl`), stored in request extensions next to the token. No identity → request refused | Every action must be attributable to a Rudder account |
| Audit log | Token check (`GET /apiaccounts/token`) on every request, even when it doubles the Rudder round trip of API tools; the whole request runs in a `request{account=<id>}` span, so every log line carries the account id (rmcp passes the span to its tasks; `spawn_blocking` work enters it explicitly). One `info` line per request: account name, rights, `Mcp-Method`, `Mcp-Name`, HTTP status (`http_status`) | Every action must be attributable to a Rudder account in the logs |
| Token handling | `ApiToken` wraps a `secrecy::SecretString` (as in `relayd`, `rudder-package`); exposed only to build the outgoing header, marked sensitive | Tokens must not leak into logs or `Debug` output |
| Rudder API | `reqwest` (native-tls, like `relayd`), base URL from `api.url`, **https only** (startup fails otherwise), 30 s timeout; `api.tls_skip_verify = true` disables certificate checks (as `relayd`'s `DangerousNone`), with a startup warning. Connection errors logged with their full cause chain | Tokens are sent to this URL. Skip-verify exists for the self-signed certificate of a default install |
| API errors | Non-2xx from Rudder → tool result with `isError: true` and Rudder's body | The model sees why (e.g. 403 for a read-only token) and can explain it |
| Technique compilation, method metadata | `rudderc` binary run as a subprocess (`--directory <tmp> build`, `lib --format json`), like the webapp, through one helper; 30 s timeout, killed on timeout, temp dir removed after | The `rudderc` library keeps its user error count in process-wide statics never reset (`compiler.rs`): in-process, one bad technique would fail every later compilation |
| Template rendering | Template module engines used as a library (`rudder-module-template`, `engine` made `pub`), always `Mode::Sandboxed`, on the blocking thread pool | Pure and bounded (minijinja fuel), unlike `rudderc` no global state; templates and data come from the model, so they are untrusted |
| Template engines | minijinja only: no `engine` argument, and unknown arguments rejected (`deny_unknown_fields`) so a requested engine is never silently replaced | Mustache ignores the sandboxed mode (`{{> name}}` partials read files from the working directory, FIXME in `engine/mustache.rs`); Jinja2 spawns a Python process per render and needs Python with `jinja2` on the server |
| Client guidance | Server instructions in `src/instructions.md` (included at build time, sent in `server/discover`): how tools fit together; tool descriptions cover each tool alone | Instructions cost context in every conversation: keep them short, workflows only. Prompts and resources later, for named workflows and reference material |
| Documentation | rudderc docs (`policies/rudderc/docs`: technique syntax, example, module docs) embedded with `include_str!`, served whole per topic by the `documentation` tool | Same source as rudderc's docs, no copy to sync; whole topics as they are already split and small (2-23 KB); a tool rather than MCP resources, as not all clients let the model read resources. Breaks `cargo package` of the crate (paths outside it), irrelevant as it is not published |
| Scenarios | MCP prompts, user-triggered: `write_technique(goal)` (text in `src/write_technique.md`) lists the steps and tools, and embeds the technique syntax as every run needs it | Costs context only when used, unlike server instructions. Ends at showing the technique: importing it into Rudder stays a human action |
| Configuration | TOML file (`/opt/rudder/etc/rudder-mcp.conf`, `-c/--config` to override; example in `rudder-mcp.conf`): `[server]` listen, read_only; `[api]` url, tls_skip_verify; `[rudderc]` path. Every key optional, unknown keys rejected, values parsed at load (socket address, https URL), missing file = startup error. Only `RUST_LOG` stays in the environment | One place for settings, like `relayd`; typos and invalid values stop startup with the file location |
| Deployment | systemd unit (`systemd/rudder-mcp.service`), `DynamicUser`, sandboxed | The server only reads its (secret-free, world-readable) configuration file, no privileges |
| Exit codes | As `relayd`: 0 stopped, 1 failure while running, 2 configuration error (file unreadable or invalid), 3 other startup error (HTTP client, listen address). Unit: `Restart=on-failure` with `RestartPreventExitStatus=2 3`. Errors logged with their cause chain | Startup errors are not fixed by restarting: no restart loop, the unit fails with the reason in the journal |
| Tests | Unit tests inline (config parsing, token parsing, template sandbox, `rudderc` wrapper against a fake `rudderc` script); end-to-end tests in `src/tests.rs`: the server as `start()` builds it, against a mock Rudder API, over HTTP with stateless MCP requests. `make check` (lint + nextest) in `mcp/Makefile` | The wrapper is tested, not `rudderc` itself (cargo cannot build it for this crate). The mock API is plain HTTP through a test-only `ApiUrl::for_tests`: no certificates in the tests; the https check has its own unit test |
| Shutdown | SIGTERM/SIGINT → graceful shutdown (in-flight requests finish) | `systemctl stop` exits cleanly, `TimeoutStopSec` bounds the wait |
| Logging | stdout, `info` by default, no timestamps when `JOURNAL_STREAM` is set | journald adds its own timestamps |

## Checked against a real Rudder (9.2.0~beta2)

- `GET /apiaccounts/token`: 200 with the account for a valid token, 401 for an unknown one, as
  the auth check assumes.
- Default install certificate is self-signed (`UID=root`, no hostname): `api.tls_skip_verify`
  needed.
- Node queries (`where`) leave out policy servers unless `select=nodeAndPolicyServer`.
- Unknown node or rule ids give a 500 ("... was not found"), not a 404: passed to
  the model as a tool error with Rudder's message. Worth reporting on the Rudder side.
- Inventory sizes for one server: default level ~1.4 KB, `software` ~100 KB, `processes` ~54 KB.

## Known rmcp quirks

- On a legacy `initialize`, rmcp falls back to `get_info().protocol_version` without checking
  it against `supported_protocol_versions()` (`negotiate_protocol_version` in
  `rmcp/src/service/server.rs`). `get_info()` therefore sets `2026-07-28` explicitly; otherwise
  legacy clients would be accepted with `2025-11-25`. Worth reporting upstream.

## Scope

Prompts: `write_technique` (goal in, guided technique writing).

Current tools:

- `whoami` (read, local): caller's account (name, id), rights (`ro`/`rw`/`acl`) and whether the
  server offers write tools; built from the token check, no extra Rudder call
- `node_info` (read, `GET /nodes/{id}` or `GET /nodes?where=<hostname eq>&select=nodeAndPolicyServer`,
  as queries otherwise leave out the Rudder server and relays): one node, by id (UUID
  or `root`) or exact hostname, `default` inventory level plus requested sections (enum of the
  `full` level sections). Only a valid id reaches the URL path; anything else is a hostname sent as
  an encoded query value. One API call either way; no match or duplicate hostnames give a tool
  error listing ids. Rudder's node JSON returned as is
- `compliance` (read): no argument → `GET /compliance` + `GET /compliance/rules?level=1`
  (concurrent), global rate and one line per rule, worst first; `node` (id or hostname, resolved
  with one extra call) → `GET /compliance/nodes/{id}`; `rule` (id, only `[A-Za-z0-9_-]` reaches the
  path: system rules have ids like `hasPolicyServer-root`) → `GET /compliance/rules/{id}`. One
  generic walker for both trees (same objects, different order; a rule's `directives` view is
  used, not its duplicate `nodes` view): what is not compliant is shown down to the failing values
  and their report messages, compliant parts only counted, output capped at 300 lines. Empty
  details are shown as "no compliance data", not as a failing 0%
- `rule_info` (read, `GET /rules/{id}`, then `GET /directives/{id}` and `GET /groups/{id}` for
  each directive and target group, concurrently with `join_all` in the request task so the
  account span stays on their logs): status (with Rudder's reason, e.g. "No policy defined"),
  directives (technique, version, policy mode), targets split include/exclude (plain `group:` /
  `special:` strings or composites), groups with node count. A directive or group that cannot be
  fetched (e.g. 403 for an ACL token) is shown with the reason, the rest still comes. Ids from
  Rudder's response go through the same path check (`is_object_id`) as ids from the model
- `system_info` (read, `GET /system/info`): the response `data` without `system.jvm.cmd` (the JVM
  command line: ~3 KB of the ~3.5 KB, noise for the model, and it exposes internal paths and
  settings); other fields kept as is, so new ones come through
- `server_health` (read, `GET /system/status` + `GET /system/healthcheck`, called concurrently):
  one text summary, `Status: <global>` then one `[<status>] <name>: <message>` line per check. One
  tool rather than two, as the status alone is a single word
- `compile_technique` (read, local): YAML technique in, `rudderc` output plus `technique.cf`,
  `technique.ps1` and `metadata.xml` out, or the diagnostics. Uses the method library in
  `/var/rudder/ncf`; techniques with a `resources/` directory are not supported
- `methods` (read, local): method library metadata from `rudderc lib --format json --stdout`
  (library in `/var/rudder/ncf`). Without argument: one line per method (id, name, description,
  deprecation), as the full export (~2 KB per method) is too large for one answer. With
  `method`: that method's full metadata, minus its server-side `source` path. `rudderc` runs on
  each call (~50 ms), no cache
- `documentation` (read, local): one rudderc doc topic (`technique-syntax`, `technique-example`,
  `module-template`, `module-augeas`, `module-commands`, `module-system-updates`, `module-secedit`)
- `render_template` (read, local): minijinja template + JSON object data in, rendered text or
  the template error with its location out. Rendering matches the module on a node (strict
  undefined variables, module filters), minus unsandboxed features (`lookup`, `include`)
- `reload_groups` (write, `POST /system/reload/groups`): Rudder's JSON as is

Open questions:


- Template rendering: minijinja fuel caps compute but not memory; a template can still allocate a
  lot.

- TLS: a custom CA file setting would allow verifying a self-signed certificate instead of
  skipping verification.

Planned tools, read-only first:

- `search`, `explain_non_compliance`, `recent_changes`, `inventory_query`
  (Rudder REST API)
- Writes only through change requests (human approval in the UI) where available
