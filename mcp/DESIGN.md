# rudder-mcp: design summary

A prototype [MCP](https://modelcontextprotocol.io) server that lets AI assistants inspect a
Rudder server and help write Rudder techniques. This is the high-level view; the decision-by-
decision detail is in [ARCHITECTURE.md](ARCHITECTURE.md).

## Goal

Answer the questions people actually ask ("what state is my server in", "why is this node not
compliant", "write me a technique that…") with a small set of tools that give short, readable
answers, while the Rudder server stays in charge of who may do what.

## Shape

- **A Rust daemon next to the Rudder server**, built on the official Rust MCP SDK (`rmcp`), like
  the other Rudder daemons (`relayd`).
- **Streamable HTTP, stateless protocol only** (MCP `2026-07-28`): no sessions to keep, every
  request stands alone, answers are plain JSON. Simple to run, restart, proxy and scale.
- **Nothing stored.** The server keeps no data and holds no credentials.

## Security model

- **The caller's own Rudder API token** is forwarded to Rudder on every call. Rudder's
  permissions are the real security boundary; the MCP server adds no privilege of its own.
- **Every request is identified.** The token is checked with Rudder on each request (local
  tools included) and must resolve to an API account, so every action is logged with the
  account that made it. Unknown or unidentifiable tokens are refused, and the check fails
  closed when Rudder cannot be reached.
- **Read-only by default.** Write tools only exist when the configuration allows them; tools
  also carry read-only/destructive hints so clients know when to ask for confirmation.
- **Model input is untrusted.** Ids only reach URL paths after validation, the generic API tool
  only reaches documented `GET` endpoints, templates always render in sandboxed mode, and
  tokens never appear in logs.
- **HTTPS to Rudder only**, with an explicit, warned-about option to skip certificate checks
  for the self-signed certificate of a default install.

## Tool design

- **Task-oriented tools, not one per endpoint.** Rudder's API has ~186 endpoints; exposing them
  all would drown the model and return raw, oversized data. Instead a dozen tools each answer
  one kind of question, combining API calls and summarizing: compliance walks the whole tree
  down to the failing report, rule info resolves directive and group names, system info drops
  the 3 KB JVM command line.
- **A generic escape hatch for the rest.** `api_search` + `api_get` reach any documented
  read-only endpoint through an index built from Rudder's own OpenAPI source: the pattern used
  by other API vendors' MCP servers, without one tool per endpoint.
- **Bounded, actionable output.** Large sections are opt-in, long answers are capped with a hint
  on how to narrow them, and errors tell the model what to do next ("use one of these ids").
- **Guidance at three levels:** tool descriptions for each tool, short server instructions for
  how tools fit together, and user-triggered prompts for full workflows (`write_technique`).

## Reuse of Rudder itself

- **Techniques are compiled by `rudderc`**, run as a subprocess exactly like the webapp does
  (its library keeps global state that would break a long-running server).
- **Templates are rendered by the template module's own engine**, so a template checked here
  behaves as it will on nodes.
- **Documentation and the API index are embedded at build time** from the repository (rudderc
  docs, OpenAPI source): always in sync with the code the server is built from.

## Operations

- **One TOML configuration file**, strictly checked at startup (unknown keys and invalid values
  are rejected with their location).
- **systemd service**, running as a throwaway user in a sandbox, with graceful shutdown and
  distinct exit codes so configuration errors do not cause restart loops.
- **Audit-friendly logs:** one line per request with the account, the MCP method and tool, and
  the result; every line of a request carries the account id.

## Quality

- **Automated tests** at two levels: unit tests next to the code, and end-to-end tests running
  the real server against a mock Rudder API.
- **Checked against a real Rudder 9.2 server and a real MCP client (Claude Code)**, which found
  and fixed actual issues (policy servers missing from hostname lookups, misleading 0% compliance).

## Known limits and next steps

- **Coverage:** planned tools include search, recent changes and inventory queries; writes only
  through Rudder's change requests (human approval), if at all.
- **Escape hatch:** large responses are cut rather than filtered; a filter parameter, or a
  "code mode" (the model writes code against the API in a sandbox), would go further.
- **Deployment:** not yet packaged or wired into CI; remote clients need TLS in front of the
  server; a custom CA setting would be safer than skipping certificate checks.
- **Authentication:** tokens are configured by hand in clients; OAuth would bring single
  sign-on for multi-user deployments.
