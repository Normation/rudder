#!/bin/sh

# Entry point for the "rudder package" command.

if [ "${RUDDER_PKG_COMPAT}" = "1" ]; then
  exec /opt/rudder/share/python/rudder-pkg/rudder-pkg "$@"
else
  exec /opt/rudder/bin/rudder-package "$@"
fi
