#!/bin/bash

set -xe # -x # to print every line as it occurs
name=$1

dir=/tmp/rudderc/tester/
cfjson_tester=/opt/rudder/share/rudder-lang/tools/cfjson_tester
technique_path=/var/rudder/configuration-repository/techniques/ncf_techniques/1.0/technique.cf

mkdir -p $dir/

# Take original technique an make a json
$cfjson_tester ncf-to-json $technique_path $dir/technique.json

# Take json and produce a rudder-lang technique
cargo run -- --translate -i $dir/technique.json

# Take rudder lang technique and compile it into cf file
# output format is generated behind the scenes, it actually is a cf file, not rl
cargo run -- -i $dir/technique.rl

# take generated cf file a new json
$cfjson_tester ncf-to-json $dir/technique.rl.cf $dir/technique.rl.cf.json

# TODO compare generated json
$cfjson_tester compare-json $dir/technique.json $dir/technique.rl.cf.json

# TODO compare generated cf files
$cfjson_tester compare-cf $technique_path $dir/technique.rl.cf
# diff --width=210 --suppress-common-lines --side-by-side $technique_path $dir/technique.rl.cf
