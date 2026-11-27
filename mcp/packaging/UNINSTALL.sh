#!/bin/sh

# Uninstall the Rudder MCP server from a Rudder server, undoing INSTALL.sh
# The configuration is kept, unless called with --purge

set -e

case "$1" in
  "") purge=false ;;
  --purge) purge=true ;;
  *) echo "Usage: $0 [--purge]" >&2; exit 1 ;;
esac

# Unload rudder-apache-mcp.conf first: no route to a stopped service
rm -f /opt/rudder/etc/rudder-apache-mcp.conf
if [ -d /etc/apache2 ]; then
  systemctl reload apache2
else
  systemctl reload httpd
fi

# Also succeeds when the service is already gone
systemctl disable --now rudder-mcp 2>/dev/null || true
rm -f /etc/systemd/system/rudder-mcp.service
systemctl daemon-reload
systemctl reset-failed rudder-mcp 2>/dev/null || true

rm -f /opt/rudder/bin/rudder-mcp

if [ "${purge}" = true ]; then
  rm -rf /opt/rudder/etc/rudder-mcp
fi
