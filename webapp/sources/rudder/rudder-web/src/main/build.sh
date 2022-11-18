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
npm ci --no-audit

if [ "$RELEASE" = true ]; then
  gulp --production
elif [ "$WATCH" = true ]; then
  gulp watch
else
  gulp
fi
