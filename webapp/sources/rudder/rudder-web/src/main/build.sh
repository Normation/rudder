#!/bin/sh

# Takes a --release parameter for optimization
# Takes a --quick mode to only copy local sources

LOCAL=false
RELEASE=false

if [ "$1" = "--release" ]; then
  RELEASE=true
fi
if [ "$1" = "--local" ]; then
  LOCAL=true
fi

set -ex

JS_OUT="webapp/javascript/"
JS_LIBS="$JS_OUT/libs/"
CSS_OUT="webapp/style/"
CSS_LIBS="$CSS_OUT/libs"

# To add or update a dependency run:
#
#   npm install PACKAGE@VERSION

# Work locally
cd "$(dirname "$0")"

if [ "$RELEASE" = true ]; then
  # Ensure clean state for release
  rm -rf $JS_OUT $CSS_OUT node_modules
fi

if [ "$LOCAL" = false ]; then
  # Ensure correct versions
  npm ci --omit=dev --no-audit
fi

# JS dependencies
mkdir -p $JS_LIBS
# npm
cp node_modules/*/dist/*.min.js* $JS_LIBS
cp node_modules/*/js/*.min.js* $JS_LIBS
cp node_modules/*/dist/**/*.min.js* $JS_LIBS
cp node_modules/datatables.net-plugins/sorting/natural.js $JS_LIBS/dataTables.naturalSorting.js
cp node_modules/showdown-xss-filter/showdown-xss-filter.js $JS_LIBS
# Local JS
cp -r javascript/* $JS_OUT
# Local elm
if [ "$LOCAL" = false ]; then
  elm/build.sh "$@"
fi
# Minify (exclude elm file as they are already minified)
#
# Disabled for now as it breaks angular
#
#if [ "$RELEASE" = true ]; then
#  find $JS_OUT \( -name elm -o -name '*.min.js' \) -prune -o -name '*.js' -exec \
#    terser --mangle --compress --output {} {} \;
#fi

# CSS dependencies
mkdir -p $CSS_LIBS
# npm
cp node_modules/*/dist/**/*.min.css* $CSS_LIBS
# Local CSS
cp -r style $CSS_OUT