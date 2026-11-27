# Rudder MCP server

An [MCP](https://modelcontextprotocol.io) server that lets AI assistants query a Rudder server.
It runs on the Rudder server, behind its Apache, at `https://<server>/rudder/mcp`.

It only reads: nothing it provides changes Rudder.

## What it provides

Tools, called by the model:

| Tool | What it gives |
|---|---|
| `get_current_account` | The API account behind the token and its rights: checks that the connection works |
| `get_server_info` | Rudder version, relays, OS, node counts, plugins, webapp status, global compliance |
| `list_nodes` | The accepted nodes, all or those matching a Rudder node search |
| `get_node` | A node's configuration and inventory, including software and available updates |
| `get_node_compliance` | A node's compliance, detailing what is not compliant, with the agent's messages |
| `get_node_policies` | A node's rules, directives (with their parameters), groups and properties |
| `get_technique` | A technique, with its YAML definition for editor techniques |

Prompts, picked by the user in their client:

| Prompt | What it does |
|---|---|
| `audit_policies` | Audits a node's policies for conflicts and inconsistencies, or a technique against Rudder's best practices |

The tools are not a stable interface and will change between versions: scripts should use the
Rudder API.

## Use

**1. Get an API token.** In Rudder, *Administration > API accounts*, create an account for the
MCP client and copy its token. A read-only (`ro`) account is enough for every tool. With the
api-authorization plugin, an `acl` account or a user token also works, limited to what it is
allowed to read: see the rights each tool needs in [DESIGN.md](DESIGN.md#rudder-rights).

The token is the only credential: each tool call is checked with Rudder and runs with that
account's rights, the MCP server adds none of its own. Do not use Rudder's system token.

**2. Add the server to your MCP client**, as a Streamable HTTP server at
`https://<server>/rudder/mcp`, with the token sent as `Authorization: Bearer <token>`. For
example, with Claude Code:

```sh
claude mcp add --transport http rudder https://<server>/rudder/mcp \
  --header "Authorization: Bearer <token>"
```

or in the JSON configuration most clients use:

```json
{
  "mcpServers": {
    "rudder": {
      "type": "http",
      "url": "https://<server>/rudder/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

The client must trust the Rudder server's certificate.

**3. Check it**: ask the assistant which Rudder account it uses (`get_current_account`). A
missing or rejected token is reported with the reason.

## Install

On the Rudder server, as root, from the extracted archive:

```sh
./INSTALL.sh
```

It installs:

| File | To |
|---|---|
| `rudder-mcp` | `/opt/rudder/bin/` |
| `main.conf`, `logging.conf` | `/opt/rudder/etc/rudder-mcp/` (kept if they already exist) |
| `rudder-mcp.service` | `/etc/systemd/system/` |
| `rudder-apache-mcp.conf` | `/opt/rudder/etc/`, included by the Rudder webapp Apache configuration |

then enables and restarts the `rudder-mcp` service, and reloads Apache. Running it again upgrades.

## Uninstall

On the Rudder server, as root, from the extracted archive:

```sh
./UNINSTALL.sh
```

It removes the files above, stops and disables the service, and reloads Apache. The
configuration in `/opt/rudder/etc/rudder-mcp/` is kept, unless called with `--purge`.

## Configure

Settings are in `/opt/rudder/etc/rudder-mcp/main.conf` and `logging.conf`, all optional; the
defaults are shown in the files. Restart the service after a change:

```sh
systemctl restart rudder-mcp
```

Logs are in the journal: `journalctl -u rudder-mcp`.

`VERSION` gives the version and the commit the archive was built from.
