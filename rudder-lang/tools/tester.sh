#!/bin/bash

# 1. Rudderc - Takes Technique Editor JSON and produces a rudder-lang technique
# 2. Rudderc - Takes the rudder-lang technique and compiles it into a cf file
# 3. Rudderc - Takes the rudder-lang technique and compiles it into a dsc file
# 4. Rudderc - Takes the rudder-lang technique and produce a new json
# 5. Script - Compares original / generated JSON files
# 6. Script - Compares original / generated CF files (if any)
# 7. Script - Compares original / generated DSC files (if any)
env="prod"
declare status=()
status_logs=(
  "JSON -> RL : Takes Technique Editor JSON and produces a rudder-lang technique         "
  "RL -> JSON : Takes the rudder-lang technique and compiles it back into a JSON file    "
  "diff json  : Compares original / generated JSON files                                 "
  "RL -> CF   : Takes the rudder-lang technique and compiles it into a CFEngine file     "
  "diff cf    : If any, compares original / generated CF files                           "
  "RL -> DSC  : Takes the rudder-lang technique and compiles it into a DSC file          "
  "diff dsc   : If any, compares original / generated DSC files                          "
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
  echo " <technique_name>: can be either a technique name from the techniques production directory or a JSON technique an path (absolute or relative)."
  echo " <technique_category>: Use with production environment only. The location of a technique depends on its category"
  echo " If using a technique path, please remove the extension file"
  echo " Example of working command (dev env): tester.sh --dev --keep ./tests/techniques/simplest/technique"
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
if [ ${env} = "dev" ] && [ -f "${technique}.json" ]
then
  technique_path="${technique}.json"
elif [ ! -f "${technique_path}.json" ]
then
  echo "Cannot find either of ${technique_path}.json nor ${technique}.json"
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

# 1. Rudderc - Takes Technique Editor JSON and produces a rudder-lang technique
# 2. Rudderc - Takes the rudder-lang technique and compiles it back into a JSON file
# 3. Script - Compares original / generated JSON files
# 4. Rudderc - Takes the rudder-lang technique and compiles it into a cf file
# 5. Script - Compares original / generated CF files (if any)
# 6. Rudderc - Takes the rudder-lang technique and compiles it into a dsc file
# 7. Script - Compares original / generated DSC files (if any)

if [ ${env} = "dev" ] 
then
  ##########################
  # DEVELOPMENT EVIRONMENT #
  ##########################

  # first, generate rl from json. Cannot do tests if this one fails, so it has to be treated separatly
  if (set -x ; ${rudderc} save -j -i "${technique}.json" --config-file=${config_file})
  then
    # json -> rl, if success compare json
    status+=("SUCCESS")
    if (set -x ; ${rudderc} technique read -i "${technique}.rl" -o "${technique}.rl.json" --config-file=${config_file})
    then
      status+=("SUCCESS")
      (set -x ; ${cfjson_tester} compare-json --config-file="${config_file}" "${technique}.json" "${technique}.rl.json")
      [ $? -eq 0 ] && status+=("SUCCESS") || status+=("ERROR")
    else
      status+=("ERROR")
    fi
    # rl -> cf, if success compare cf
    if (set -x ; ${rudderc} compile -j -f "cf" -i "${technique}.rl" --config-file=${config_file})
    then
      status+=("SUCCESS")
      (set -x ; ${cfjson_tester} compare-cf --config-file="${config_file}" "${technique}.cf" "${technique}.rl.cf")
      [ $? -eq 0 ] && status+=("SUCCESS") || status+=("ERROR")
    else
      status+=("ERROR")
    fi
    # rl -> dsc, if success compare dsc
    if (set -x ; ${rudderc} compile -j  -f "dsc" -i "${technique}.rl" --config-file=${config_file})
    then
      status+=("SUCCESS")
      (set -x ; ${cfjson_tester} compare-dsc --config-file="${config_file}" "${technique_path}" "${technique}.rl.dsc")
      [ $? -eq 0 ] && status+=("SUCCESS") || status+=("ERROR")
    else
      status+=("ERROR")
    fi
  else
    status+=("ERROR")
  fi

  #######################
  # OUTPUT LOOP RESULTS #
  #######################
  declare -i index=0
  for log_to_print in "${status_logs[@]}"
  do
    if [ ${#status[@]} -gt ${index} ]
    then
      echo "${log_to_print}-> ${status[${index}]}"
    else
      echo "${log_to_print}-> NOT DONE"
    fi
    index=${index}+1
  done

else
  ##########################
  # PRODUCTION ENVIRONMENT #
  ##########################

  trace="/var/log/rudder/rudder-lang/unhandled_errors.log"
  log_results="/var/log/rudder/rudder-lang/results.log"
  logpath="/var/log/rudder/rudder-lang/${technique}"
  mkdir -p "${logpath}"
  chmod +rx "${logpath}"
  # be careful, file order matters
  logfiles=(
    "${logpath}/rudderc.log"
    "${logpath}/compare_json.log"
    "${logpath}/compare_cf.log"
    "${logpath}/compare_dsc.log"
  )
  is_save_success="null"
  is_read_success="null"
  is_diff_json_success="null"
  is_compile_cf_success="null"
  is_diff_cf_success="null"
  is_compile_dsc_success="null"
  is_diff_dsc_success="null"

  # JSON results log fmt - prepare to receive log data (remove last ']')
  if [ ! -f "${log_results}" ]
  then
    echo -e '[\n]' > "${log_results}"
  fi
  # in case log root array is not empty: 
  perl -0777 -i -ne 's/\}\n]\n$/\},\n/; print $last = $_; END{print $last}' "${log_results}" &> "/dev/null"
  # in case log root array is empty:
  perl -0777 -i -ne 's/\[\n]\n$/[\n/; print $last = $_; END{print $last}' "${log_results}" &> "/dev/null"

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

  # first, generate rl from json. Cannot do tests if this one fails, so it has to be treated separatly
  # redirects stderr to a logfile (2>>) so that errors can be retrieved by Rudder team
  if (set -x ; ${rudderc} save -j -i "${technique_path}.json" -o "${technique_path}.rl" 2>> "${logfiles[0]}")
  then
    # json -> rl, if success compare json
    status+=("SUCCESS") && is_save_success="true"

    if (set -x ; ${rudderc} technique read -i "${technique_path}.rl" -o "${test_dir}/${technique}.rl.json" 2>> "${logfiles[0]}")
    then
      status+=("SUCCESS") && is_read_success="true"
      # success prints nothing. failure = JSON error to stdout -> redirected to a logfile (>>) so that errors can be retrieved by Rudder team. Unhandled python errors -> raw trace file
      (set -x ; ${cfjson_tester} compare-json "${technique_path}.json" "${test_dir}/${technique}.rl.json" >> "${logfiles[1]}" 2>> "${trace}")
      [ $? -eq 0 ] && status+=("SUCCESS") && is_diff_json_success="true" || status+=("ERROR") && is_diff_json_success="false"
    else
      truncate -s-1 "${logfiles[0]}" ; echo "," >> "${logfiles[0]}" # keep json format valid
      status+=("ERROR" "NOT DONE") && is_read_success="false"
    fi
    # rl -> cf, if success compare cf
    if (set -x ; ${rudderc} compile -j -f "cfengine" -i "${technique_path}.rl" -o "${test_dir}/${technique}.cf" 2>> "${logfiles[0]}")
    then
      status+=("SUCCESS") && is_compile_cf_success="true"
      (set -x ; ${cfjson_tester} compare-cf "${technique_path}.cf" "${test_dir}/${technique}.rl.cf" >> "${logfiles[2]}" 2>> "${trace}")
      [ $? -eq 0 ] && status+=("SUCCESS") && is_diff_cf_success="true" || status+=("ERROR") && is_diff_cf_success="false"
    else
      truncate -s-1 "${logfiles[0]}" ; echo "," >> "${logfiles[0]}" # keep json format valid
      status+=("ERROR" "NOT DONE") && is_compile_cf_success="false"
    fi
    # rl -> dsc, if success compare dsc
    if (set -x ; ${rudderc} compile -j -f "dsc" -i "${technique_path}.rl" -o "${test_dir}/${technique}.dsc" 2>> "${logfiles[0]}")
    then
      status+=("SUCCESS") && is_compile_dsc_success="true"
      (set -x ; ${cfjson_tester} compare-dsc "${technique_path}.dsc" "${test_dir}/${technique}.rl.dsc" >> "${logfiles[3]}" 2>> "${trace}")
      [ $? -eq 0 ] && status+=("SUCCESS") && is_diff_dsc_success="true" || status+=("ERROR") && is_diff_dsc_success="false"
    else
      truncate -s-1 "${logfiles[0]}" ; echo "," >> "${logfiles[0]}" # keep json format valid
      status+=("ERROR" "NOT DONE") && is_compile_dsc_success="false"
    fi
  else
    truncate -s-1 "${logfiles[0]}" ; echo "," >> "${logfiles[0]}" # keep json format valid
    status+=("ERROR") && is_save_success="false"
  fi

  # prints result in a unique log file to know if tests went well overall, can be computed for later use
  global_log="  {
    \"technique\": \"${technique}\",
    \"save\": ${is_save_success},
    \"read\": ${is_read_success},
    \"diff json\": ${is_diff_json_success},
    \"compile cf\": ${is_compile_cf_success},
    \"diff cf\": ${is_diff_cf_success},
    \"compile dsc\": ${is_compile_dsc_success},
    \"diff dsc\": ${is_diff_dsc_success}
  }"
  echo -e "${global_log}," >> "${log_results}"

  # clean new log trace entry if no uncatched errors were found
  if [ -f "${trace}" ] && [[ $(tail -n 1 "${trace}") == "=== ${technique} ===" ]]
  then
    head -n -1 "${trace}" > "${test_dir}/tmp.trace"
    mv "${test_dir}/tmp.trace" "${trace}"
  fi


  # in case log_results root array is empty, just delete it:
  [ $(cat "${log_results}" | wc -l) = 1 ] && rm -f ${log_results}
  # in case log_results root array is not empty:
  # note: -i modifies the input file ; -n add loops around -e
  perl -i -ne 's/,\n$/\n]\n/ if eof; print $last = $_; END{print $last COUCOUC}' "${log_results}" &> "/dev/null"

  # JSON log fmt - end of file (repush last ']' now that content has been added)
  for log in "${logfiles[@]}"
  do
    # in case log root array is empty, just delete it:
    [ $(cat "${log}" | wc -l) = 1 ] && rm -f ${log}
    # in case log root array is not empty:
    perl -i -ne 's/,\n$/\n]\n/ if eof; print $last = $_; END{print $last}' "${log}" &> "/dev/null"
  done
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
    echo "Executed rudder-lang tests in ${test_dir}"
  else
    echo "Executed rudder-lang tests in ${technique_path}.cf parent dir"
  fi
fi

[[ ${#status[@]} -eq 7 ]] && exit 0 || exit 1
