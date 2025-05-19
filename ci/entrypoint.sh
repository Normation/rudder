#!/bin/sh.real
#
# A universal ENTRYPOINT wrapper:
#   - Logs the entire invocation (with env)
#   - Then execs the requested command (or falls back to default)

# 1) Assemble the command string
if [ $# -gt 0 ]; then
  CMD_STR="$*"
else
  # If no args given, fall back to your default CMD
  CMD_STR="/bin/sh id"
fi

# 2) Log it (reuse your existing logger script)
#    first arg “entry” indicates entrypoint logging
/etc/command_logger.sh entry "$CMD_STR"

# 3) Exec it (so signals/stdio behave normally)
exec "$@"