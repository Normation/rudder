#!/bin/bash
set -e

./cure53.sh &

TEST_DIR="$(git rev-parse --show-toplevel)/policies/rudderc/tests/cases/general"
RUDDERC="$(git rev-parse --show-toplevel)/target/debug/rudderc"
LIBRARY="$(git rev-parse --show-toplevel)/policies/rudderc/tests/lib"

find "${TEST_DIR}" -name "*.ps1" -not -path "*/target/*" -print0 | while read -d $'\0' TECHNIQUE
do
  echo "Generating expected image file for the test technique $TECHNIQUE"
  WORK_DIR=$(mktemp -d)
  TECHNIQUE_DIR=$(dirname $TECHNIQUE)
  cp -r $TECHNIQUE_DIR/* $WORK_DIR/
  $RUDDERC --directory $WORK_DIR build --library $LIBRARY --output $TECHNIQUE_DIR
done
