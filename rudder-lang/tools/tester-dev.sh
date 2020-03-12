#!/bin/bash

set -e # -x # to print every line as it occurs
name=$1
dir=$PWD/tests/test_files/tester/$name
cfjson_tester=$PWD/tools/cfjson_tester

mkdir -p $dir/

# Take original technique an make a json
$cfjson_tester ncf-to-json $dir/technique.cf $dir/technique.json /tools/rudderc-dev.conf

# Take json and produce a rudder-lang technique
cargo run -- --translate --config ./tools/rudderc-dev.conf -i $dir/technique.json

# Take rudder lang technique and compile it into cf file
# output format is generated behind the scenes, it actually is a cf file, not rl
cargo run -- --compile --config ./tools/rudderc-dev.conf -i $dir/technique.rl

# take generated cf file a new json
$cfjson_tester ncf-to-json $dir/technique.rl.cf $dir/technique.rl.cf.json /tools/rudderc-dev.conf

# TODO compare generated json
$cfjson_tester compare-json $dir/technique.json $dir/technique.rl.cf.json /tools/rudderc-dev.conf

# TODO compare generated cf files
$cfjson_tester compare-cf $dir/technique.cf $dir/technique.rl.cf /tools/rudderc-dev.conf