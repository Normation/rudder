#!/bin/sh.real
# wrap every /bin/sh -c … invocation

FULL_CMD="$*"
# log it
/etc/command_logger.sh sh "$FULL_CMD"
# exec the real shell
exec /bin/sh.real "$@"