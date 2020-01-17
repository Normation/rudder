#!/bin/bash

set -xe
name=$1
dir=$PWD/tests

# Take original technique an make a json
$dir/helpers/ncf ncf-to-json $dir/translate/${name}.cf $dir/target/${name}.json

# Take json and produce a rudder-lang technique
cargo run -- --translate -i $dir/target/${name}.json -o $dir/target/${name}.rl

# Take rudder lang technique and compile it into cf file
cargo run -- --technique -i $dir/compile/${name}.rl -o $dir/target/${name}.rl

# take generated cf file a new json
$dir/helpers/ncf ncf-to-json $dir/target/${name}.rl.cf $dir/target/${name}.rl.cf.json

# TODO compare generated json
$dir/helpers/ncf compare-json $dir/target/${name}.json $dir/target/${name}.rl.cf.json

# TODO compare generated cf files
$dir/helpers/ncf compare-cf $dir/translate/${name}.cf $dir/target/${name}.rl.cf
