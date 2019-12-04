#!/bin/bash

set -xe
name="xx"

export LD_LIBRARY_PATH=/home/bpeccatte/Rudder/ncf/cfe/usr/lib

# Take original technique an make a json
./ncf ncf-to-json ${name}.cf

# Take json and produce a rudder-lang technique
cargo run -- --translate -i ${name}.json -o ${name}.rl

# Take rudder lang technique and compile it into cf file
cargo run -- --technique -i ${name}.rl -o ${name}.rl

# take generated cf file a new json
./ncf ncf-to-json ${name}.rl.cf

# TODO compare generated json
./ncf compare-json ${name}.json ${name}.rl.json

# TODO compare generated cf files
./ncf compare-cf ${name}.cf ${name}.rl.cf
