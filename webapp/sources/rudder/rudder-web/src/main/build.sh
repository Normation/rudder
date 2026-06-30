#!/bin/sh

set -e

# Takes a --release parameter for optimization
# Takes a watch parameter to enable watch mode

RELEASE=false
WATCH=false
if [ "$1" = "--release" ]; then
  RELEASE=true
fi
if [ "$1" = "--watch" ]; then
  WATCH=true
fi

# Work locally
cd "$(dirname "$0")"

if [ "$RELEASE" = true ]; then
  # Ensure clean state for release
  rm -rf node_modules
fi

# Ensure correct versions
npm_config_loglevel=error npm ci --no-audit

if [ "$RELEASE" = true ]; then
  npx gulp --production
elif [ "$WATCH" = true ]; then
  npx gulp watch
else
  npx gulp
fi

# elm-format check
echo "> npm run elm-format-check"
fail="$(mktemp)"
if npm run elm-format-check > "$fail" 2>&1
then
  rm "$fail"
  echo "> All Elm files are formatted \o/"
else
  cat "$fail" >&2
  rm "$fail"
  exit 1
fi
