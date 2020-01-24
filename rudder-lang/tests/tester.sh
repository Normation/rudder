#!/bin/bash

set -xe
name=$1
dir=$PWD/tests

mkdir -p dir/target/

# Take original technique an make a json
$dir/helpers/cfjson_tester ncf-to-json $dir/test_files/cf/${name}.cf $dir/test_files/json/${name}.json

# Take json and produce a rudder-lang technique
cargo run -- --translate -i $dir/test_files/json/${name}.json -o $dir/test_files/rl/${name}.rl

# Take rudder lang technique and compile it into cf file
cargo run -- --compile -i $dir/test_files/rl/${name}.rl -o $dir/test_files/target/${name}.rl

# take generated cf file a new json
$dir/helpers/cfjson_tester ncf-to-json $dir/test_files/target/${name}.rl.cf $dir/test_files/target/${name}.rl.cf.json

# TODO compare generated json
$dir/helpers/cfjson_tester compare-json $dir/test_files/json/${name}.json $dir/test_files/target/${name}.rl.cf.json

# TODO compare generated cf files
$dir/helpers/cfjson_tester compare-cf $dir/test_files/cf/${name}.cf $dir/test_files/target/${name}.rl.cf
