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
- **Every tool call is authenticated and identified**, before it reaches the tool. The token is
  checked with Rudder (`GET /rudder/api/latest/apiaccounts/token`) and must resolve to an API
  account, so every action can be attributed. Otherwise the call fails with the reason, as a
  tool error the model can relay:

  | Case | Reason given |
  |---|---|
  | No or malformed `Authorization` header | no token, how to send one |
  | Token unknown to Rudder | token rejected |
  | Token valid but not allowed to read its own account | must be allowed to read it |
  | Rudder unreachable, no account or unknown rights in the answer | could not be checked |

  The check fails closed: a token that cannot be checked is refused. It costs one Rudder API
  call per tool call.
- **The other requests need no token** (connecting, listing the tools, getting the prompts):
  they only expose the
  server's code, not Rudder's data, and cost no Rudder call. A client stays connected while
  Rudder is unreachable, e.g. the webapp restarting, and its tool calls work again once Rudder is
  back. The check is in the tool call path rather than in front of MCP, as only the body tells a
  tool call apart (the `Mcp-Method` header is optional for older protocol versions).
- **The account's rights are always read**: `none`, `ro`, `rw`, `acl`, or none given, as Rudder
  does for user tokens (api-authorization plugin), whose rights are those of the owning user. An
  unknown value is refused rather than guessed.
- **Tokens never leak**: kept in a `secrecy::SecretString`, so absent from `Debug` output and
  logs, and marked sensitive in the header forwarded to Rudder (`X-API-Token`).
- **HTTPS to Rudder**, plain HTTP only to a local (loopback) server, checked at startup. Redirects
  are not followed, so tokens never leave the configured server.

## Tools

**The tools are not a stable interface.** Their names, arguments and answers will change as we
learn what serves the models and their users: tools may be added, merged, split or removed, and
fields renamed or dropped, without deprecation. Their consumers are models reading the tool
descriptions at each connection, not programs: nothing should be built on their exact shape.
Scripts and integrations should use the Rudder API, which is the stable interface.

Tools are named in snake_case, verb then noun (`get_node`, `list_nodes`), the usual convention
of MCP servers: `get_` for one object, `list_` for several, `search_` for a free-text search.

Tools answer compact JSON, Rudder's data passed through where possible: new fields come through
without a change here, and the field names carry the meaning. Plain text is kept for summaries
of data too large to send whole (e.g. compliance), where size matters more than shape.

Tools that read Rudder call its API with the caller's token, so Rudder applies the caller's
rights. A Rudder error is returned to the model as a failed tool call with Rudder's answer, so
it can tell the user rather than retry. On 403 it also names the right the endpoint requires
(e.g. `compliance_read`), which Rudder's answer does not. A tool making several calls reports
every failed one, as each can need a different right.

- `get_current_account`: the Rudder API account behind the caller's token and its rights. Built from the
  token check, no extra Rudder call. Doubles as the "does it work" check for clients.
- `get_server_info` (`GET /system/info`, `GET /system/status` and `GET /compliance`, concurrently):
  Rudder version, relays, OS, node counts, plugins, plus the webapp `status` and the global
  `compliance` (percentage of each report status, empty without policies). Passed through as
  JSON, without the JVM: its command line is most of the answer, noise for the model, and it
  exposes the server's internal paths and settings. Fails if any call fails.
- `list_nodes`: the accepted nodes, all or those matching a Rudder node search (`GET /nodes`),
  to find the ones to look at with the other tools.
  - **Criteria**: those of Rudder's node search (`where`, `{objectType, attribute, comparator,
    value}`, and an `and`/`or` composition), passed through rather than a closed list of
    filters: they cover software, properties, groups, OS, IP addresses... and change with Rudder
    versions. The tool description documents the most useful ones; an invalid one gets Rudder's
    error, which lists the valid object types, attributes or comparators to retry with. The
    criteria are typed and serialized here, so no value can change the query's structure. The
    Rudder server and relays are included, as without criteria
    (`select=nodeAndPolicyServer`).
  - **Answer**: `{total, nodes}`, each node with its id, hostname and OS (`include=minimal,os`):
    enough to pick one, `get_node` gives the rest. Sorted by
    hostname, every match listed, with their number (`total`), which spares the model counting.
    Rudder has no paging, nor does the tool: on a large installation, an unfiltered call answers
    every node, about 150 bytes each, so narrowing is left to the model's criteria.

- `get_node`: a node's configuration and inventory. Not its runtime state (see
  `get_node_compliance`).
  - **Lookup**: the node is designated as below. `GET /nodes/{id}` for an id, `GET /nodes`
    searching the exact hostname otherwise. That search returns the node itself, so there is no
    extra call to resolve it.
  - **Default content**: Rudder's default level (OS, IP addresses, RAM, policy server, agent,
    policy mode, properties, state, dates), passed through minus what only costs context: the
    internal machine id, the agent capabilities, the `Missing version` placeholder.
  - **Other sections** (`details`): on request, by their Rudder API name rather than from a
    closed list, as they change with Rudder versions and Rudder ignores unknown names. Refused:
    - names that are not plain (a `,` would add other `include` values);
    - the detail levels (`full` is every section);
    - `managementTechnologyDetails` (the agent's certificate and user).
  - **Software** (`software`): only filtered by name, as the full list is large. The filter is
    non-empty, and also brings the matching updates (Rudder only sends them with the software).
    Matching more than 100 packages is an error asking for a narrower filter.
  - **Policy mode**: `policyMode` is resolved to the mode that applies, with `policyModeSource`
    (`node`, `global`, or `forced` when the global mode is not overridable). It comes from the
    two global settings read alongside the node (`node_read` is enough for them). If they can't be
    read (e.g. an ACL token without their paths), the node is still answered, with Rudder's raw
    `policyMode` and the reason in `policyModeUnresolved`: one field is not worth failing the
    whole answer.

- `get_node_compliance`: a node's compliance (`GET /compliance/nodes/{id}`), the node designated as
  below. The full tree (rule → directive → component → value) is too large to pass through, so
  it is summarized as indented plain text:
  - **Every rule** applied to the node, with its rate and the share of each report status: an
    overview of the node.
  - **Below a rule not fully compliant**, only what is not: directives, blocks and components,
    down to the failing values with the agent's report messages. Compliant siblings are only
    counted. A status is compliant as in Rudder (`success*`, `auditCompliant`,
    `auditNotApplicable`), any other is shown, including those this version does not know.
  - **Skipped directives** (overridden by another rule) are listed with the overriding rule.
  - **No reports** is said, rather than shown as a 0% rate.
  - **At most 300 lines**, then the number of lines left out.

  Rudder's answer is parsed into types: a change in its shape is a tool error, not a wrong
  summary.

- `get_node_policies` lists the rules and directives in a node’s compliance tree, including
  override information. It requests level 4 to include directive references across Rudder versions.
  Pass a listed directive id to read its current parameter tree and the exact technique
  version it references. Parameter expressions are returned as configured.
  - **Groups and properties**: the overview also has what decides the node's policies: the
    groups it belongs to (`{id, name}`, sorted by name), the targets of its rules, and its
    properties as Rudder resolves them (`GET /nodes/{id}/inheritedProperties`), the values
    directive expressions read. Each property has its value, and in `definedIn` where it is set
    (node, groups, global), closest first, from Rudder's hierarchy minus the values at each
    level, which repeat. Rudder's resolution errors are kept (per property in `error`, for the
    node in `propertiesError`): they block the node's policy generation. Rudder has no endpoint
    for a node's groups, so `GET /groups` reads every group with its members and keeps those
    listing the node: on a large installation, this answer is the largest of the three calls.
    The three calls are concurrent, and the overview fails with every failed one.
  - **One technique shape**: `id`, `name`, `version`, `source` (`built-in` or `editor`) for
    every technique, and for editor techniques their `technique.yml` as Rudder sends it, in
    `content`. Rudder only sends that YAML for them: the metadata comes from the directive and
    the YAML's `name`.
  - **Named parameters**: rudderc names the directive variable of an editor technique's
    parameter by its uppercased id, usually a UUID. Such variables are renamed to the
    parameter's name, from the YAML's `params` (`profile` rather than `2628FA31-...`). Built-in techniques' variables
    already have meaningful names, and are kept.

- `get_technique`: a technique by id (`GET /techniques/{id}/{version}`, `format=yaml`), in the
  shape of `get_node_policies`: metadata, plus the YAML definition of editor techniques. The
  version can be omitted when there is only one (`GET /techniques/{id}` lists them); with
  several, the error lists them. The id and version are checked as plain path segments before
  any call, so the error is the caller's rather than Rudder's.

### Designating a node

Users name nodes by hostname, Rudder by id. Every tool working on a node takes a single `node`
argument accepting either, resolved the same way:

- **A UUID (any case) or `root`** (the Rudder server) is a node id, used as is in the API path.
- **Anything else is a hostname**, matched exactly against the inventory hostname (usually fully
  qualified) with a node search (`GET /nodes`, `nodeHostname` `eq`), which includes the Rudder
  server and relays (`select=nodeAndPolicyServer`). Tools needing an id pay one extra call.
- **No match, or several**, is a tool error telling the model what to give instead: the exact
  hostname, or one of the matching nodes' ids.
- **A hostname matching no node** gets a second search for the usual near misses: hostnames with
  the same first label, in any case and domain (`regex` `(?i)<label>(\..*)?`, `include=minimal`),
  e.g. `web1` for `web1.example.com`. The error lists up to 10 of them with their ids, for the
  model to retry with. Only labels of hostname characters (`[A-Za-z0-9_-]`) are searched, so no
  regex syntax from the caller reaches Rudder; a failed search only leaves the list out.

Only ids of that shape reach the URL path; a hostname is always an encoded query value, so no
input can reach another endpoint. The cost: a node with a non-UUID id (other than `root`) can
only be designated by its hostname.

### Rudder rights

Every tool only reads (`GET`). What the caller's token needs depends on its kind:

- **`ro` or `rw` API account**: nothing more, every tool works.
- **`acl` API account** (api-authorization plugin): `GET` on each path below, the token check's
  included.
- **User token** (api-authorization plugin): the owning user needs the rights below.
- **System token** (Rudder's own, told by its account id `rudder-system-api-account`, as
  Rudder sends no `authorizationType` for it): every tool works, `system` rights. Each tool call with it logs a
  warning, as it has every right: clients should use a dedicated API account.
- **`none`**: refused at the token check of each tool call.

| Tool | Rudder API calls (`GET`, under `/rudder/api/latest/`) | Rights (user token) |
|---|---|---|
| Every tool call (token check) | `apiaccounts/token` | `administration_read` or `userAccount_read` |
| `get_current_account` | none | none |
| `get_server_info` | `system/info`, `system/status`, `compliance` | `administration_read`, `compliance_read` |
| `list_nodes` | `nodes` | `node_read` |
| `get_node` | `nodes/{id}` or `nodes`; `settings/global_policy_mode`, `settings/global_policy_mode_overridable` (optional) | `node_read` |
| `get_node_compliance` | `nodes` (hostname only); `compliance/nodes/{id}` | `node_read` (hostname only), `compliance_read` |
| `get_node_policies` | `nodes` (hostname only), `compliance/nodes/{id}`; for the overview, `groups`, `nodes/{id}/inheritedProperties`; for details, `directives/{id}`, `techniques/{name}/{version}` | `node_read`, `compliance_read`; for the overview, `group_read`; for details, `directive_read`, `technique_read` |
| `get_technique` | `techniques/{id}` (no version given), `techniques/{id}/{version}` | `technique_read` |

The rights are those of each endpoint's `authz` in the webapp's `EndpointsDefinition.scala`
(any one of them is enough). The tools pass the same names to `CallerApi::get`, which reports
them on 403. Update this table with every tool.

## Prompts

Prompts are tasks the user picks in their client, written for the model: what to read with which
tools, what to look for, how to report. A prompt only builds text from its arguments and the
documents embedded in the server; it reads nothing from Rudder and needs no token. The model then
calls the tools, with the caller's token and rights. Reviews that need the model's judgment are
prompts rather than tools: a tool would only hand over data and a checklist, and the model could
not ask for it unprompted anyway.

- `audit_policies` (`node`, `technique`, `version`, all optional): an audit, reporting only,
  findings most severe first, each with where it is, the check broken, why it matters and the
  fix.
  - **A node**: its policies as a whole (`get_node`, `get_node_policies` and its details for each
    directive, `get_node_compliance`), checked for conflicts between directives (the same object
    managed differently or twice, dependencies between directives, missing or failing
    properties, the wrong platform), and each of its editor techniques against the best
    practices.
  - **A technique**: read with `get_technique`, checked against the best practices.
  - **Neither**: a `technique.yml` the user gives, e.g. one being written.
  - **The checklists are embedded** in the binary, in Markdown for people:
    [`best-practices.md`](src/prompts/best-practices.md), the technique best practices, and
    [`node-consistency.md`](src/prompts/node-consistency.md), sent for a node only. The best
    practices are those of the rudderc documentation minus what the model cannot check by reading
    the technique: running `rudderc check`/`build`, the webapp logs, the editor setup.

## Operations

- **Configuration directory**, as `relayd` (`-c`, default `/opt/rudder/etc/rudder-mcp/`):
  `main.conf` (listen address, default `127.0.0.1:3031`; Rudder URL, default
  `http://127.0.0.1:8080`) and `logging.conf` (level and filters). TOML, every setting optional,
  unknown keys rejected. Samples in [`config/`](config/).
- **Logging** shared with `relayd` through `rudder_cli::logs::daemon`. Every line of a request,
  rmcp's included, is in a `mcp_request` span with the client address, the MCP method and tool
  (`Mcp-Method`, `Mcp-Name`, or for a tool call without them, from its body) and, once a tool
  call's token is accepted, the account id, name and rights. One `info` line per request with
  the HTTP status and duration; refused tool calls are logged with their reason. At `debug`,
  each Rudder API call is in a `rudder_api` span (method, path) with its status and duration.
  rmcp is kept to warnings by default, its `info` logs bury the request log.
- **systemd unit** ([`systemd/rudder-mcp.service`](systemd/rudder-mcp.service)): throwaway user
  in a sandbox. Exit codes 2 (configuration) and 3 (other startup error) are not restarted, as a
  restart would not fix them; 1 is.
