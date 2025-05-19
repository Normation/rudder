#!/usr/bin/env bash
# /etc/bash_trap.sh

# once-only guard
if [[ -z "$_CMD_LOGGER_TRAP" ]]; then
  export _CMD_LOGGER_TRAP=1
  trap '/etc/command_logger.sh bash "$BASH_COMMAND"' DEBUG
fi