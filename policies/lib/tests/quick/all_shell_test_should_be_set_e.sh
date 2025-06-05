#!/bin/sh

set -e
GIT_ROOT="$(git rev-parse --show-toplevel)"
NCF_TREE=$GIT_ROOT/policies/lib/tree

# All tests written in shell should use "set -e". This test checks those tests.
# (It is thus somewhat a meta test test.)

ALL_SHELL_TESTS=`find "${GIT_ROOT}/policies/lib/tests/quick" -name "*.sh"`

ERRORS=0
for file in ${ALL_SHELL_TESTS}
do
  if ! grep -E "^[ 	]*set[ 	]+-e" ${file} > /dev/null; then
    ERRORS=`expr ${ERRORS} + 1`
    echo "Test ${file} is missing the \"set -e\" declaration"
  fi
done

if [ ${ERRORS} -eq 0 ]; then
  echo "R: $0 Pass"
else
  echo "R: $0 FAIL"
fi
exit $ERRORS
