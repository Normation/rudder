#!/bin/sh

# Install or upgrade the Rudder MCP server on a Rudder server, from the extracted archive

set -e

cd "$(dirname "$0")"

install -m 755 rudder-mcp /opt/rudder/bin/rudder-mcp
# Keep the configuration on upgrade
mkdir -p /opt/rudder/etc/rudder-mcp
for conf in main.conf logging.conf; do
  [ -f "/opt/rudder/etc/rudder-mcp/${conf}" ] || install -m 644 "${conf}" "/opt/rudder/etc/rudder-mcp/${conf}"
done
install -m 644 rudder-mcp.service /usr/lib/systemd/system/rudder-mcp.service
install -m 644 rudder-apache-mcp.conf /opt/rudder/etc/rudder-apache-mcp.conf

systemctl daemon-reload
systemctl enable rudder-mcp
systemctl restart rudder-mcp

# Load rudder-apache-mcp.conf
if [ -d /etc/apache2 ]; then
  systemctl reload apache2
else
  systemctl reload httpd
fi
