#!/bin/bash

env="prod"

#####################
# ARGUMENTS & USAGE #
#####################

allowed_options="d|k"
for param in "$@"
do
  # force dev env optional parameter
  if [ "$1" = "--dev" ] || [[ "$1" =~ ^-[${allowed_options}]?d[${allowed_options}]?$ ]]
  then
    env="dev"
  fi

  # cleanup optional parameter
  if [ "$1" = "--keep" ] || [[ "$1" =~ ^-[${allowed_options}]?k[${allowed_options}]?$ ]]
  then
    cleanup=no
  fi

  # shift on option
  if [[ "$1" =~ ^--?[a-z]* ]]
  then
    shift
  fi
done

if [ "$1" = "" ]
then
  echo "Usage tester.sh [--dev] [--keep] <technique>"
  echo " --dev or -d: force using a development environnement (meaning no json logs and local rudder repo rather than production files)"
  echo " --keep or -k: keep temporary files after cleanup"
  echo " <technique>: can be either a technique name from ncf directory or an absolute path"
  exit 1
fi


###############
# SETUP PATHS #
###############

technique="$1"

# Default values for rudder server
self_dir=$(dirname $(readlink -e $0))
test_dir=$(mktemp -d)

# cfjson_tester is always in the same directory as tester.sh
cfjson_tester="${self_dir}/cfjson_tester"

# Detect technique path
technique_path="/var/rudder/configuration-repository/techniques/ncf_techniques/${technique}/1.0/technique.cf"
if [ ${env} = "dev" ] && [ -f "$1" ]
then
  technique_path="$1"
elif [ ! -f "${technique_path}" ]
then
  echo "Cannot find either of ${technique_path} nor $1"
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
if [ ${env} = "prod" ]
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
  ${cfjson_tester} ncf-to-json --config-file=${config_file} "${technique_path}" "${test_dir}/${technique}.json" \
  && ${rudderc} --config-file=${config_file} --translate -i "${test_dir}/${technique}.json" \
  && ${rudderc} --config-file=${config_file} -i "${test_dir}/${technique}.rl" \
  && ${cfjson_tester} ncf-to-json --config-file="${config_file}" "${test_dir}/${technique}.rl.cf" "${test_dir}/${technique}.rl.cf.json" \
  && ${cfjson_tester} compare-json --config-file="${config_file}" "${test_dir}/${technique}.json" "${test_dir}/${technique}.rl.cf.json" \
  && ${cfjson_tester} compare-cf --config-file="${config_file}" "${technique_path}" "${test_dir}/${technique}.rl.cf"

else
  ##########################
  # PRODUCTION ENVIRONMENT #
  ##########################

  trace="/var/log/rudder/rudder-lang/unhandled_errors.log"
  logpath="/var/log/rudder/rudder-lang/${technique}"
  mkdir -p "${logpath}"
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

  ${cfjson_tester} ncf-to-json "${technique_path}" "${test_dir}/${technique}.json" >> "${logfiles[0]}" 2>> "${trace}" \
    && ${rudderc} -j --translate -i "${test_dir}/${technique}.json" &>> "${logfiles[1]}" \
    && ${rudderc} -j -i "${test_dir}/${technique}.rl" &>> "${logfiles[2]}" \
    && ${cfjson_tester} ncf-to-json "${test_dir}/${technique}.rl.cf" "${test_dir}/${technique}.rl.cf.json" >> "${logfiles[3]}" 2>> "${trace}" \
    && ${cfjson_tester} compare-json "${test_dir}/${technique}.json" "${test_dir}/${technique}.rl.cf.json" >> "${logfiles[4]}" 2>> "${trace}" \
    && ${cfjson_tester} compare-cf "${technique_path}" "${test_dir}/${technique}.rl.cf" >> "${logfiles[5]}" 2>> "${trace}"


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
    [[ $(cat "${log}" | wc -l) == 1 ]] && rm -f ${log}
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
  echo "Done testing in ${test_dir}"
fi

