#!/bin/bash

env="prod"
declare -i status=0
status_logs=(
  "Step 1: original cf to json"
  "Step 2: rudderc translate"
  "Step 3: rudderc compile"
  "Step 4: generated cf to json"
  "Step 5: compare jsons"
  "Step 6: compare cfs"
)
declare -i status=0
status_logs=(
  "Step 1: original cf to json    "
  "Step 2: rudderc translate      "
  "Step 3: rudderc compile        "
  "Step 4: generated cf to json   "
  "Step 5: compare jsons          "
  "Step 6: compare cfs            "
)

#####################
# ARGUMENTS & USAGE #
#####################

# this script is designed to take a cf technique input

for param in "$@"
do
  if [ "$1" = "--dev" ] || [ "$1" = "-d" ]
  then
    # force dev env optional parameter
    env="dev"
    shift
  elif [ "$1" = "--keep" ] || [ "$1" = "-k" ]
  then
    # cleanup optional parameter
    cleanup=no
    shift
  # shift on option
  elif [[ "$1" =~ ^--?[a-z]* ]]
  then
    echo "Unsupported option $1"
    exit 1
  fi
done

if [ -z "$1" ]
then
  echo "Usage tester.sh [--dev|-d] [--keep|-k] <technique_name> [<technique_category>]"
  echo " --dev or -d: force using a development environnement (meaning no json logs and local rudder repo rather than production files)"
  echo " --keep or -k: keep temporary files after cleanup"
  echo " <technique_name>: can be either a technique name from the production techniques directory or a cfengine technique an absolute path (or starting by \"./\")."
  echo " <technique_category>: when using the production environment, the location of a technique depends on its category"
  exit 1
fi

###############
# SETUP PATHS #
###############

[[ "$1" =~ ^(.*)\.cf?$ ]]
if [ ! -z ${BASH_REMATCH[1]} ]
then
  technique="${BASH_REMATCH[1]}"
else
  technique="${1}"
fi

technique="$1"
category="$2"

# Default values for rudder server
self_dir=$(dirname $(readlink -e $0))
test_dir=$(mktemp -d)
chmod +rx "${test_dir}"

# cfjson_tester is always in the same directory as tester.sh
cfjson_tester="${self_dir}/cfjson_tester"

# Detect technique path
# requires an extension format to be used
technique_path="/var/rudder/configuration-repository/techniques/${category}/${technique}/1.0/technique"
if [ ${env} = "dev" ] && [ -f "${technique}.cf" ]
then
  technique_path="${technique}.cf"
elif [ ! -f "${technique_path}.cf" ]
then
  echo "Cannot find either of ${technique_path}.cf nor ${technique}.cf"
  exit 1
fi

# Detect rudderc and cfjson_tester configuration
config_file="/opt/rudder/etc/rudderc.conf"
[ ${env} = "prod" ] || config_file="${self_dir}/rudderc-dev.conf"
if [ ! -f "${config_file}" ]
then
  echo "Cannot find ${config_file}"
  exit 1
fi

rudderc="/opt/rudder/bin/rudderc"
if [ "${env}" = "prod" ]
then
  if [ ! -f "${rudderc}" ]
  then
    echo "Cannot find ${rudderc}"
    exit 1
  fi
else
  rudderc="cargo run -- "
fi


###############################
# EXECUTE SCRIPTS AND PROGRAM #
###############################

# 1. Script - Takes a CF technique and produces a JSON file
# 2. Rudderc - Takes this JSON and produces a rudder-lang technique
# 3. Rudderc - Takes the rudder-lang technique and compiles it into a cf file
# 4. Script - Takes this generated cf file and produces a new json
# 5. Script - Compares original / generated JSON files
# 6. Script - Compares original / generated CF files

if [ ${env} = "dev" ] 
then
  ##########################
  # DEVELOPMENT EVIRONMENT #
  ##########################

  set -x

  ${cfjson_tester} ncf-to-json --config-file=${config_file} "${technique_path}" "${technique}.json" && status=$((${status}+1)) \
    && ${rudderc} --config-file=${config_file} --translate -s "${technique}.json" && status=$((${status}+1)) \
    && ${rudderc} --config-file=${config_file} -s "${technique}.rl" && status=$((${status}+1)) \
    && ${cfjson_tester} ncf-to-json --config-file="${config_file}" "${technique}.rl.cf" "${technique}.rl.cf.json" && status=$((${status}+1)) \
    && ${cfjson_tester} compare-json --config-file="${config_file}" "${technique}.json" "${technique}.rl.cf.json" && status=$((${status}+1)) \
    && ${cfjson_tester} compare-cf --config-file="${config_file}" "${technique_path}" "${technique}.rl.cf" && status=$((${status}+1))

  set +x

else
  ##########################
  # PRODUCTION ENVIRONMENT #
  ##########################

  trace="/var/log/rudder/rudder-lang/unhandled_errors.log"
  logpath="/var/log/rudder/rudder-lang/${technique}"
  mkdir -p "${logpath}"
  chmod +rx "${logpath}"
  # be careful, file order matters
  logfiles=(
    "${logpath}/ncftojson_original.log"
    "${logpath}/rudderc_translate.log"
    "${logpath}/rudderc_compile.log"
    "${logpath}/ncftojson_generated.log"
    "${logpath}/compare_json.log"
    "${logpath}/compare_cf.log"
  )

  # JSON log fmt - prepare to receive log data (remove last ']')
  for log in "${logfiles[@]}"
  do
    if [ ! -f "${log}" ]
    then
      echo -e '[\n]' > "${log}"
    fi
    # in case log root array is not empty: 
    perl -0777 -i -ne 's/\}\n]\n$/\},\n/; print $last = $_; END{print $last}' "${log}" &> "/dev/null"
    # in case log root array is empty:
    perl -0777 -i -ne 's/\[\n]\n$/[\n/; print $last = $_; END{print $last}' "${log}" &> "/dev/null"
  done

  # prepare new entry for log trace or create log trace
  ([ -f "${trace}" ] && echo -e "\n=== ${technique} ===" >> "${trace}") || touch "${trace}"

  set -x

  cp "${technique_path}.json" "${test_dir}/${technique}.json" >> "${logfiles[${status}]}" 2>> "${trace}" && status=$((${status}+1)) \
    && ${rudderc} -j --translate -s "${test_dir}/${technique}.json" &>> "${logfiles[${status}]}" && status=$((${status}+1)) \
    && ${rudderc} -j -s "${test_dir}/${technique}.rl" -f "cfengine" &>> "${logfiles[${status}]}" && status=$((${status}+1)) \
    && ${cfjson_tester} ncf-to-json "${test_dir}/${technique}.rl.cf" "${test_dir}/${technique}.rl.cf.json" >> "${logfiles[${status}]}" 2>> "${trace}" && status=$((${status}+1)) \
    && ${cfjson_tester} compare-json "${test_dir}/${technique}.json" "${test_dir}/${technique}.rl.cf.json" >> "${logfiles[${status}]}" 2>> "${trace}" && status=$((${status}+1)) \
    && ${cfjson_tester} compare-cf "${technique_path}.cf" "${test_dir}/${technique}.rl.cf" >> "${logfiles[${status}]}" 2>> "${trace}" && status=$((${status}+1))

  set +x

  # clean new log trace entry if no uncatched errors were found
  if [ -f "${trace}" ] && [[ $(tail -n 1 "${trace}") == "=== ${technique} ===" ]]
  then
    head -n -1 "${trace}" > "${test_dir}/tmp.trace"
    mv "${test_dir}/tmp.trace" "${trace}"
  fi

  # JSON log fmt - end of file (repush last ']' now that content has been added)
  for log in "${logfiles[@]}"
  do
    # in case log root array is empty, just delete it:
    [ $(cat "${log}" | wc -l) = 1 ] && rm -f ${log}
    # in case log root array is not empty:
    perl -i -ne 's/,\n$/\n]\n/ if eof; print $last = $_; END{print $last}' "${log}" &> "/dev/null"
  done
fi

#######################
# OUTPUT LOOP RESULTS #
#######################

declare -i index=0
for log_to_print in "${status_logs[@]}"
do
  if [ ${status} -gt ${index} ]
  then
    current_status="success"
  else
    current_status="error"
  fi
  echo "${log_to_print}-> ${current_status}"
  index=${index}+1
done

if [ ${status} -eq 6 ]
then
  echo "Everything ran well with the testing loop"
else
  echo "An error occurred during the testing loop, with '${technique_path}.cf'"
fi

###########
# CLEANUP #
###########

if [ "${cleanup}" != "no" ]
then
  rm -rf "${test_dir}"
else
  if [ "${env}" = "prod" ]
  then
    echo "Done testing in ${test_dir}"
  else
    echo "Done testing in ${technique_path}.cf parent dir"
  fi
fi

[[ ${success} -eq 6 ]] && exit 0 || exit 1
