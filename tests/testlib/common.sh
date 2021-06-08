#!/bin/sh

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GIT_ROOT=$SCRIPT_DIR/../..
NCF_TREE=$GIT_ROOT/tree

export GIT_ROOT NCF_TREE
