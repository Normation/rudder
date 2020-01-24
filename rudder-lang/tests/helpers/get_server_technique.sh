#!/bin/bash

# in vagrant machine run the script as root with the technique name as the only parameter
# it will print the cf technique content, just copy/paste result into:
# 'tests/test_files/cf/technique_name.cf'

echo "\e[0;32m"
cat /var/rudder/configuration-repository/techniques/ncf_techniques/$1/1.0/technique.cf
echo "\e[0;37m\n"
