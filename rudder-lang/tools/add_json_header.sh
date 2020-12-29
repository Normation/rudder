#!/bin/bash

# techniques=(./tests/techniques/*)
techniques=(./eutelsat_techniques/*/1.0/*.json)

success_count=0
errors=()
for technique in ${techniques[*]}
do
  echo ${technique}
  echo -e "{\n    \"type\": \"ncf_technique\",\n    \"version\": 2,\n   \"data\": $(cat ${technique})\n}" > ${technique}
done

echo "NICE"
