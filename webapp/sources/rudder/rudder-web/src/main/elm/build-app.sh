#!/bin/bash

# we want that all elm-stuff stay in src/main/elm
# whatever the path from which this script is called
ELM_DIR="$( cd "$( dirname "$0" )" && pwd )"
PROJECTS=("notifications" "healthcheck" "editor" "onboarding")
for PROJECT in ${PROJECTS[*]}; do
  cd ${ELM_DIR}/${PROJECT}
  elm make sources/rudder-${PROJECT}.elm --output=generated/rudder-${PROJECT}.js --optimize
  cp generated/rudder-${PROJECT}.js ${ELM_DIR}/../webapp/javascript/rudder/elm/
done
