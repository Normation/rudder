#!/bin/sh

set -e
set -x

# Wait until cf-execd has fetched initial policies
while [ ! -f /var/rudder/cfengine-community/inputs/promises.cf ]
do 
  sleep 1
done

/opt/rudder/bin/cf-serverd --no-fork --inform
