#!/bin/bash

set -e
set -x # -x # to print every line as it occurs

if [ "$1" = "-k" ]
then
  cleanup=no
  shift
fi

if [ "$1" = "" ]
then
  echo "Usage tester.sh [-k] <technique>"
  echo " -k: keep temporary files after cleanup"
  echo " <technique>: can be either a technique name from ncf directory or an absolute path"
  exit 1
fi

technique_name="$1"

# Default values for rudder server
self_dir=$(dirname $(readlink -e $0))
test_dir=$(mktemp -d)
#old test_dir=/tmp/rudderc/tester/$technique_name
#mkdir -p $dir

# cfjson_tester is always in the same directory as tester.sh
cfjson_tester="${self_dir}/cfjson_tester"
#old cfjson_tester=/opt/rudder/share/rudder-lang/tools/cfjson_tester

# Detect technique path
technique_path="/var/rudder/configuration-repository/techniques/ncf_techniques/${technique}/1.0/technique.cf"
[ -f "${technique_path}" ] || technique_path="$1"
if [ ! -f "${technique_path}" ]
then
  echo "Cannot find /var/rudder/configuration-repository/techniques/ncf_techniques/${technique}/1.0/technique.cf nor $1"
  exit 1
fi

# Detect rudderc and cfjson_tester configuration
config_file="/opt/rudder/etc/rudderc.conf"
[ -f "${config_file}" ] || config_file="${self_dir}/rudderc.conf"
if [ ! -f "${config_file}" ]
then
  echo "Cannot find /opt/rudder/etc/rudderc.conf nor ${self_dir}/rudderc.conf"
  exit 1
fi

# logs handler
logger=&>> /var/log/rudder/rudder-lang/

# Detect rudderc
rudderc="/opt/rudder/bin/rudderc"
[ -f "${rudderc}" ] || rudderc="cargo run -- "

# Take original technique an make a json
${cfjson_tester} ncf-to-json ${cfjson_config} "${technique_path}" "${test_dir}/technique.json" ${logs}

# Take json and produce a rudder-lang technique
${rudderc} --config-file "${config_file}" --translate -i "${test_dir}/technique.json"

# Take rudder lang technique and compile it into cf file
# output format is generated behind the scenes, it actually is a cf file, not rl
${rudderc} --config-file "${config_file}" -i "${test_dir}/technique.rl"

# take generated cf file a new json
${cfjson_tester} ncf-to-json --config="${config_file}" "${test_dir}/technique.rl.cf" "${test_dir}/technique.rl.cf.json"

# compare generated json
${cfjson_tester} compare-json --config="${config_file}" "${test_dir}/technique.json" "${test_dir}/technique.rl.cf.json"

# compare generated cf files
${cfjson_tester} compare-cf --config="${config_file}" "${technique_path}" "${test_dir}/technique.rl.cf"

if [ "${cleanup}" != "no" ]
then
  rm -rf "${test_dir}"
else
  echo "Done testing in ${test_dir}"
fi
