#!/bin/bash

set -e

if ! command -v elm-0.19.1 &> /dev/null
then
  echo "# ERROR: missing elm-0.19.1 binary"
  echo "# To install the right compiler version:"
  echo ""
  echo "$ curl -L -o elm-0.19.1.gz https://github.com/elm/compiler/releases/download/0.19.1/binary-for-linux-64-bit.gz"
  echo "$ gzip -d elm-0.19.1.gz"
  echo "$ chmod +x elm-0.19.1"
  echo "# then put it somewhere in your PATH"
  exit 1
fi

# we want that all elm-stuff stay in src/main/elm
# whatever the path from which this script is called
ELM_DIR="$( cd "$( dirname "$0" )" && pwd )"
PROJECTS=("Notifications" "Healthcheck" "Editor" "Onboarding" "Rules")
for PROJECT in ${PROJECTS[*]}; do
  lower=$(echo "${PROJECT}" | tr '[:upper:]' '[:lower:]')
  cd ${ELM_DIR}/${lower}
  elm-0.19.1 make --optimize sources/${PROJECT}.elm --output=generated/rudder-${lower}.js
  cp generated/rudder-${lower}.js ${ELM_DIR}/../webapp/javascript/rudder/elm/
done
