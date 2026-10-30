# Rudder MCP server

An [MCP](https://modelcontextprotocol.io) server that lets AI assistants query a Rudder server.
It runs on the Rudder server, behind its Apache, at `https://<server>/rudder/mcp`.

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
| `rudder-mcp.service` | `/usr/lib/systemd/system/` |
| `rudder-apache-mcp.conf` | `/opt/rudder/etc/`, included by the Rudder webapp Apache configuration |

then enables and restarts the `rudder-mcp` service, and reloads Apache. Running it again upgrades.

## Configure

Settings are in `/opt/rudder/etc/rudder-mcp/main.conf` and `logging.conf`, all optional; the
defaults are shown in the files. Restart the service after a change:

```sh
systemctl restart rudder-mcp
```

Logs are in the journal: `journalctl -u rudder-mcp`.

`VERSION` gives the version and the commit the archive was built from.
