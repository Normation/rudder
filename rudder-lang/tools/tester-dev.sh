#!/bin/bash

set -xe # -x # to print every line as it occurs
dir=./tests/test_files/tester/$1
cfjson_tester=./tools/cfjson_tester

# Take original technique an make a json
$cfjson_tester ncf-to-json  --config=tools/rudderc-dev.conf $dir/technique.cf $dir/technique.json

# Take json and produce a rudder-lang technique
cargo run -- --translate --config-file ./tools/rudderc-dev.conf -i $dir/technique.json

# Take rudder lang technique and compile it into cf file
# output format is generated behind the scenes, it actually is a cf file, not rl
cargo run -- --config-file ./tools/rudderc-dev.conf -i $dir/technique.rl

# take generated cf file a new json
$cfjson_tester ncf-to-json  --config=tools/rudderc-dev.conf $dir/technique.rl.cf $dir/technique.rl.cf.json

# TODO compare generated json
$cfjson_tester compare-json  --config=tools/rudderc-dev.conf $dir/technique.json $dir/technique.rl.cf.json

# TODO compare generated cf files
$cfjson_tester compare-cf  --config=tools/rudderc-dev.conf $dir/technique.cf $dir/technique.rl.cf
