#!/bin/sh

set -e

# All tests written in shell should use "set -e". This test checks those tests.
# (It is thus somewhat a meta test test.)

ALL_SHELL_TESTS=`find "${NCF_TREE}/../tests/style/" -name "*.sh"`

ERRORS=0
for file in ${ALL_SHELL_TESTS}
do
  if ! egrep "^\s*set\s+-e[\s#]*$" ${file} > /dev/null; then
    ERRORS=`expr ${ERRORS} + 1`
    echo "Test ${file} is missing the \"set -e\" declaration"
  fi
done

if [ ${ERRORS} -eq 0 ]; then
  echo "R: $0 Pass"
else
  echo "R: $0 FAIL"
fi
