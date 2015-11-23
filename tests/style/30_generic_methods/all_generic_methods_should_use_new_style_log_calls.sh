#!/bin/sh

#####################################################################################
# Copyright 2015 Normation SAS
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

# Check that all generic_methods use the new _log interface with 4 args and not the old _logger interface with 2 args

FILES_TO_CHECK=`find "${NCF_TREE}/30_generic_methods/" -name "*.cf"`
NB_ERROR=0
for f in ${FILES_TO_CHECK}
do
  NB_BUNDLE=$(egrep "[^#]*usebundle\s*=>\s*_?logger(_default|_rudder|)\(" $f | wc -l)
  if [ $NB_BUNDLE -ne 0 ]; then
    echo "File $f uses deprecated _logger interface"
    NB_ERROR=`expr $NB_ERROR + 1`
  fi
done

if [ $NB_ERROR -eq 0 ]; then
  echo "R: $0 Pass"
else
  echo "R: $0 Fail"
fi

exit $NB_ERROR
