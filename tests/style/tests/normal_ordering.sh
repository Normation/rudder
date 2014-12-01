#!/bin/sh

set -e

# Source code should respect normal ordering to avoid misunderstanding
# This can help detect bugs

ALL_TESTS=`find ${NCF_TREE} -name '*.cf' | grep -v 20_cfe_basics/cfengine`

ERRORS=0
for file in ${ALL_TESTS}
do
  ${NCF_TREE}/../tools/ordering.pl ${file}
  if [ $? -ne 0 ]; then
    ERRORS=`expr ${ERRORS} + 1`
    echo "Test ${file} has a normal ordering error"
  fi
done

if [ ${ERRORS} -eq 0 ]; then
  echo "R: $0 Pass"
else
  echo "R: $0 FAIL"
fi
exit ${ERRORS}
