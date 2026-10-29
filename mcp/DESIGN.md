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

- `whoami`: the Rudder API account behind the caller's token and its rights. Built from the
  token check, no extra Rudder call. Doubles as the "does it work" check for clients.

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
