#!/bin/sh

set -e

# Check that all generic_methods bundles that start with _ do not have any promises aside from "meta-promises"

ALLOWED_TYPES="vars classes methods reports"

ERRS=0
FILES_TO_CHECK=`find "${NCF_TREE}/30_generic_methods/" -name "_*.cf"`
for file in ${FILES_TO_CHECK}
do
  PROMISE_TYPES=`cat ${file} | egrep -v "^\s*#?\s*$" | egrep "^\s*[a-z]+:(\s+|#|$)" | sed -e "s/^\s*\(.*\):.*$/\1/" | sort | uniq`

  for found_type in ${PROMISE_TYPES}
  do
    TYPE_OK=0
    for allowed_type in ${ALLOWED_TYPES}
    do
      if [ "z${found_type}" = "z${allowed_type}" ]; then
        TYPE_OK=1
      fi
    done

    if [ ${TYPE_OK} -ne 1 ]; then
      echo "File ${file} contains a forbidden promise type (${found_type}) in an internal bundle"
      ERRS=`expr ${ERRS} + 1`
    fi
  done

done

if [ ${ERRS} -eq 0 ]; then
  echo "R: $0 Pass"
else
  echo "R: $0 FAIL"
fi
