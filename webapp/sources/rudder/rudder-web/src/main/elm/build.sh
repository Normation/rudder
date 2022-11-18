#!/bin/bash

# Call without option for a dev build
# Call with --release for an optimized and minified build

# Takes no argument and builds all elm apps

set -e

ELM="elm-0.19.1"

# we want that all elm-stuff stay in src/main/elm
# whatever the path from which this script is called
ELM_DIR="$( cd "$( dirname "$0" )" && pwd )"
DEST_DIR="${ELM_DIR}/../webapp/javascript/rudder/elm"



