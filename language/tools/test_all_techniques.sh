#!/bin/bash

[ "$1" = "-s" ] && stop="yes"
techniques=(./tests/techniques/*)

success_count=0
errors=()
for technique in ${techniques[*]}
do
  ./tools/tester.sh --dev --keep "${technique}/technique"
  result=$?
  if [ ${result} -eq 0 ]
  then
    success_count=$((success_count + 1))
  else
    errors+=("${result} error(s) found while testing ${technique}")
    if [ "${stop}" = "yes" ]; then
      echo "Err: ./tools/tester.sh --dev --keep ${technique}/technique"
      exit 1
    fi
  fi
done

echo "${success_count} out of ${#techniques[@]} techniques tested were fully successful (if hidden folders, total might be wrong)"
echo "${errors[*]}"
exit $((${#techniques[@]} - ${success_count}))
