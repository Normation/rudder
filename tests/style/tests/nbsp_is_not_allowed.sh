#!/bin/sh

set -e

# NBSP breaks scripts and cfengine code, they should never appear

ALL_TESTS=`find ${NCF_TREE}/.. -type f | grep -v flask`

ERRORS=0
for file in ${ALL_TESTS}
do
  # allow nbsp in comments
  if grep -r -P '^[^#]*\xA0' ${file} > /dev/null; then
    ERRORS=`expr ${ERRORS} + 1`
    echo "Test ${file} has a non breaking space in it"
  fi
done

if [ ${ERRORS} -eq 0 ]; then
  echo "R: $0 Pass"
else
  echo "R: $0 FAIL"
fi
exit ${ERRORS}
