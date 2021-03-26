#!/bin/bash

techniques=(./tests/techniques/*)

success_count=0
errors=()
for technique in ${techniques[*]}
do
  ./tools/tester.sh --dev --keep "${technique}/technique"
  result=$?
  [ ${result} -eq 0 ] && success_count=$((success_count + 1)) || errors+=("${result} error(s) found while testing ${technique}")
done

echo "${success_count} out of ${#techniques[@]} techniques tested were fully successful (if hidden folders, total might be wrong)"
echo "${errors[*]}"
exit $((${#techniques[@]} - ${success_count}))
