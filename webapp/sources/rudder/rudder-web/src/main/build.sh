#!/bin/bash

set -e

# Work locally
BUILD_DIR="$( cd "$( dirname "$0" )" && pwd )"

# Poor man's gulp, with infinitely fewer dependencies

# To add or update a dependency run:
#
#   npm install PACKAGE@VERSION

# Takes a --release parameter for optimization
# Takes a --watch parameter for watch mode (requires inotifywait installed)

WATCH=false
RELEASE=false
if [ "$1" = "--release" ]; then
  RELEASE=true
fi
if [ "$1" = "--watch" ]; then
  WATCH=true
fi

PATH="${BUILD_DIR}/node_modules/.bin:${PATH}"

ELM_OUT="webapp/javascript/elm/"

JS_OUT="webapp/javascript/"
JS_LIBS="$JS_OUT/libs/"

CSS_OUT="webapp/style/"
CSS_LIBS="$CSS_OUT/libs/"

success() {
  printf '\e[1;32m%12s\e[0m %s\n' "$1" "$2"
}

elm_build() {
  mkdir -p ${ELM_OUT}
  cd "${BUILD_DIR}/elm"
  apps=$(find . -name 'elm.json' -printf "%h\n" | sed "s@\./@@")

  for app in ${apps[*]}; do
    cd "${BUILD_DIR}/elm/${app}"
    if [ "$RELEASE" = true ]; then
        cmd="elm make --optimize sources/${app^}.elm --output=${ELM_OUT}/rudder-${app}.js >/dev/null \
          && terser ${ELM_OUT}/rudder-${app}.js --compress 'pure_funcs=\"F2,F3,F4,F5,F6,F7,F8,F9,A2,A3,A4,A5,A6,A7,A8,A9\",pure_getters,keep_fargs=false,unsafe_comps,unsafe' \
          |  terser --mangle --output=${ELM_OUT}/rudder-${app}.js"
    else
        cmd="elm make sources/${app^}.elm --output=${ELM_OUT}/rudder-${app}.js >/dev/null"
    fi
    sem --id front -j+0 "${cmd}"
  done
  cd "${BUILD_DIR}"
}

css() {
  mkdir -p $CSS_OUT
  cp -r style/* $CSS_OUT
  success Copied "local css"
}

js() {
  mkdir -p $JS_OUT
  cp -r javascript/* $JS_OUT
  success Copied "local js"
}

vendor_js() {
  mkdir -p $JS_LIBS
  cp node_modules/*/dist/*.min.js* $JS_LIBS
  cp node_modules/*/js/*.min.js* $JS_LIBS
  cp node_modules/*/dist/**/*.min.js* $JS_LIBS
  cp node_modules/datatables.net-plugins/sorting/natural.js $JS_LIBS/dataTables.naturalSorting.js
  cp node_modules/showdown-xss-filter/showdown-xss-filter.js $JS_LIBS
  success Copied "vendored js"
}

vendor_css() {
  mkdir -p $CSS_LIBS
  cp node_modules/*/dist/**/*.min.css* $CSS_LIBS
  success Copied "vendored css"
}

dependencies() {
  # Silence notice
  mkdir -p "${HOME}/.parallel"
  touch "${HOME}/.parallel/will-cite"
  npm ci --no-audit --silent
  success Installed "dependencies"
}

clean() {
  rm -rf $JS_OUT $CSS_OUT
  if [ "$RELEASE" = true ]; then
    rm -rf node_modules
    rm -rf elm/*/elm-stuff
  fi
}

build() {
  clean
  dependencies
  elm_build
  js
  css
  vendor_css
  vendor_js
}

build






#inotifywait -m /path -e create -e moved_to |
#    while read path action file; do
#        echo "The file '$file' appeared in directory '$path' via '$action'"
#        # do something with the file
#    done