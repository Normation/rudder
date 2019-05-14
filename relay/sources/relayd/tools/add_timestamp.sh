#!/bin/bash

n=0

while IFS= read -r line; do
    # Same as in agent with a T instead of space (iso8601 UTC)
    date=$(/bin/date -d "+${n} hour" -u "+%Y-%m-%dT%T+00:00")
    ((++n))
    printf "${date} %s\n" "$line"
done
