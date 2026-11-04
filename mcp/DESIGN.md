# rudder-mcp: design

An [MCP](https://modelcontextprotocol.io) server that lets AI assistants work with a Rudder
server. It is being built in small steps; this page describes what exists today.

## Shape

- **A Rust daemon next to the Rudder server**, in the Rudder workspace, built on the official
  Rust MCP SDK (`rmcp`) and `axum`, like `relayd`.
- **Streamable HTTP, stateless protocol only**: the endpoint is `/mcp`, no sessions are kept,
  every request stands alone and answers are plain JSON. Simple to run, restart and proxy.
- **Nothing stored, no credentials held.** The server only reads its configuration.

## Security model

- **The caller's own Rudder API token**, sent as `Authorization: Bearer <token>`, is the only
  credential. Rudder's permissions are the security boundary; the MCP server adds no privilege
  of its own.
- **Every request is authenticated and identified**, before it reaches MCP. The token is checked
  with Rudder (`GET /rudder/api/latest/apiaccounts/token`) and must resolve to an API account,
  so every action can be attributed:

  | Case | Answer |
  |---|---|
  | No or malformed `Authorization` header | 401 |
  | Token unknown to Rudder | 401 |
  | Token valid but not allowed to read its own account | 403 |
  | Rudder unreachable, no account or unknown rights in the answer | 502 |

  The check fails closed: a token that cannot be checked is refused. It costs one Rudder API
  call per MCP request.
- **The account's rights are always read**: `none`, `ro`, `rw`, `acl`, or none given, as Rudder
  does for user tokens (api-authorization plugin), whose rights are those of the owning user. An
  unknown value is refused rather than guessed.
- **Tokens never leak**: kept in a `secrecy::SecretString`, so absent from `Debug` output and
  logs, and marked sensitive in the header forwarded to Rudder (`X-API-Token`).
- **HTTPS to Rudder**, plain HTTP only to a local (loopback) server, checked at startup. Redirects
  are not followed, so tokens never leave the configured server.

## Tools

Tools answer compact JSON, Rudder's data passed through where possible: new fields come through
without a change here, and the field names carry the meaning. Plain text is kept for summaries
of data too large to send whole (e.g. compliance), where size matters more than shape.

Tools that read Rudder call its API with the caller's token, so Rudder applies the caller's
rights. A Rudder error is returned to the model as a failed tool call with Rudder's answer, so
it can tell the user rather than retry. On 403 it also names the right the endpoint requires
(e.g. `compliance_read`), which Rudder's answer does not. A tool making several calls reports
every failed one, as each can need a different right.

- `whoami`: the Rudder API account behind the caller's token and its rights. Built from the
  token check, no extra Rudder call. Doubles as the "does it work" check for clients.
- `server_info` (`GET /system/info`, `GET /system/status` and `GET /compliance`, concurrently):
  Rudder version, relays, OS, node counts, plugins, plus the webapp `status` and the global
  `compliance` (percentage of each report status, empty without policies). Passed through as
  JSON, without the JVM: its command line is most of the answer, noise for the model, and it
  exposes the server's internal paths and settings. Fails if any call fails.

### Rudder rights

Every tool only reads (`GET`). What the caller's token needs depends on its kind:

- **`ro` or `rw` API account**: nothing more, every tool works.
- **`acl` API account** (api-authorization plugin): `GET` on each path below, the token check's
  included.
- **User token** (api-authorization plugin): the owning user needs the rights below. Today
  `administration_read` and `compliance_read` cover every tool.
- **`none`**: refused at the token check.

| Tool | Rudder API calls (`GET`, under `/rudder/api/latest/`) | Rights (user token) |
|---|---|---|
| Every request (token check) | `apiaccounts/token` | `administration_read` or `userAccount_read` |
| `whoami` | none | none |
| `server_info` | `system/info`, `system/status`, `compliance` | `administration_read`, `compliance_read` |

The rights are those of each endpoint's `authz` in the webapp's `EndpointsDefinition.scala`
(any one of them is enough). The tools pass the same names to `CallerApi::get`, which reports
them on 403. Update this table with every tool.

## Operations

- **Configuration directory**, as `relayd` (`-c`, default `/opt/rudder/etc/rudder-mcp/`):
  `main.conf` (listen address, default `127.0.0.1:3031`; Rudder URL, default
  `http://127.0.0.1:8080`) and `logging.conf` (level and filters). TOML, every setting optional,
  unknown keys rejected. Samples in [`config/`](config/).
- **Logging** shared with `relayd` through `rudder_cli::logs::daemon`. Every line of a request,
  rmcp's included, is in a `mcp_request` span with the client address, the MCP method and tool
  (`Mcp-Method`, `Mcp-Name`) and, once the token is accepted, the account id and rights. One
  `info` line per request with the account name, HTTP status and duration; refusals are logged
  with their reason. At `debug`, each Rudder API call is in a `rudder_api` span (method, path) with
  its status and duration. rmcp is kept to warnings by default, its `info` logs bury the
  request log.
- **systemd unit** ([`systemd/rudder-mcp.service`](systemd/rudder-mcp.service)): throwaway user
  in a sandbox. Exit codes 2 (configuration) and 3 (other startup error) are not restarted, as a
  restart would not fix them; 1 is.
