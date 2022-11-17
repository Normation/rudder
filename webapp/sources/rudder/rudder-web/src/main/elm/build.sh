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

if ! command -v ${ELM} &> /dev/null
then
  echo "# ERROR: missing ${ELM} binary"
  echo "# To install the right compiler version:"
  echo ""
  echo "$ curl -L -o ${ELM}.gz https://github.com/elm/compiler/releases/download/${ELM}/binary-for-linux-64-bit.gz"
  echo "$ gzip -d ${ELM}.gz"
  echo "$ chmod +x ${ELM}"
  echo "# then put it somewhere in your PATH"
  exit 1
fi

build_release() {
  # Make sure we build from a clean state
  rm -rf elm-stuff
  ${ELM} make --optimize sources/${app^}.elm --output=generated/rudder-${app}.js
  terser generated/rudder-${app}.js --compress 'pure_funcs="F2,F3,F4,F5,F6,F7,F8,F9,A2,A3,A4,A5,A6,A7,A8,A9",pure_getters,keep_fargs=false,unsafe_comps,unsafe' | terser --mangle --output=generated/rudder-${app}.min.js
  # we use the same path for dev and prod so we can't really use .min.js
  mkdir -p ${DEST_DIR}
  cp generated/rudder-${app}.min.js ${DEST_DIR}/rudder-${app}.js
}

build_dev() {
  ${ELM} make sources/${app^}.elm --output=generated/rudder-${app}.js
  mkdir -p ${DEST_DIR}
  cp generated/rudder-${app}.js ${DEST_DIR}/
}

cd ${ELM_DIR}
apps=$(find . -name 'elm.json' -printf "%h\n" | sed "s@\./@@")

for app in ${apps[*]}; do
  cd ${ELM_DIR}/${app}
  if [ "$1" = "--release" ]; then
    (set -x; build_release)
  else
    (set -x; build_dev)
  fi
done
