#!/bin/sh

set -e

# Check that all generic_methods bundles are preceded with a comment

ERRS=0
FILES_TO_CHECK=`find "${NCF_TREE}/30_generic_methods/" -name "*.cf"`
for file in ${FILES_TO_CHECK}
do
  if ! cat ${file} | grep -v "^$" | egrep -B1 "^\s*bundle\s+agent\s+" | head -n1 | grep "^#"; then
    echo "File ${file} contains a bundle missing a comment!"
    ERRS=`expr ${ERRS} + 1`
  fi
done

if [ ${ERRS} -eq 0 ]; then
  echo "R: $0 Pass"
else
  echo "R: $0 FAIL"
fi
