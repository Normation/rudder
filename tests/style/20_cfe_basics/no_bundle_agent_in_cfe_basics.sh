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

# Check that there are no bundle agent within the cfe_basics files

# We check only the ncf sub-directory, because the CFEngine stdlib contains bundle agent
FILES=${NCF_TREE}/20_cfe_basics/*.cf
NB_ERROR=0
for f in $FILES
do
  NB_BUNDLE=$(egrep "^\s*bundle\s+agent" $f | wc -l)
  if [ $NB_BUNDLE -ne 0 ]; then
    echo "File $f contains $NB_BUNDLE bundle agent, but it should not contains any bundle agent"
    NB_ERROR=`expr $NB_ERROR + 1`
  fi
done

if [ $NB_ERROR -eq 0 ]; then
  echo "R: $0 Pass"
else
  echo "R: $0 Fail"
fi

exit $NB_ERROR
