#!/bin/sh

#####################################################################################
# Copyright 2013 Normation SAS
#####################################################################################
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, Version 3.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.
#
#####################################################################################
set -e

# Check that all generic_methods files contains one and only one bundle agent

FILESi_TO_CHECK=`find "${NCF_TREE}/30_generic_methods/" -name "*.cf"`
NB_ERROR=0
for f in $FILES_TO_CHECK
do
  NB_BUNDLE=$(egrep "^\s*bundle\s+agent" $f | wc -l)
  if [ $NB_BUNDLE -ne 1 ]; then
    echo "File $f contains an invalid number of bundle agent. Expected 1, found $NB_BUNDLE"
    NB_ERROR=`expr $NB_ERROR + 1`
  fi
done

if [ $NB_ERROR -eq 0 ]; then
  echo "R: $0 Pass"
else
  echo "R: $0 Fail"
fi

exit $NB_ERROR
