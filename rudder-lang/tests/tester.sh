#!/bin/bash

set -xe # -x # to print every line as it occurs
name=$1
dir=$PWD/tests/test_files/tester
cfjson_tester=$PWD/tests/helpers/cfjson_tester

mkdir -p $dir/target/

# Take original technique an make a json
$cfjson_tester ncf-to-json $dir/cf/${name}.cf $dir/json/${name}.json

# Take json and produce a rudder-lang technique
cargo run -- --translate -i $dir/json/${name}.json -o $dir/rl/${name}.rl

# Take rudder lang technique and compile it into cf file
# output format is generated behind the scenes, it actually is a cf file, not rl
cargo run -- --compile -i $dir/rl/${name}.rl -o $dir/target/${name}.rl

# take generated cf file a new json
$cfjson_tester ncf-to-json $dir/target/${name}.rl.cf $dir/target/${name}.rl.cf.json

# TODO compare generated json
$cfjson_tester compare-json $dir/json/${name}.json $dir/target/${name}.rl.cf.json

# TODO compare generated cf files
$cfjson_tester compare-cf $dir/cf/${name}.cf $dir/target/${name}.rl.cf
# diff --width=210 --suppress-common-lines --side-by-side ${dir}/cf/${name}.cf ${dir}/target/${name}.rl.cf
