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

# Check that no tests use the group "root" for perms - this works on Linux but not on most UNIXes

FILES_TO_CHECK=`find "${NCF_TREE}/../tests/" -name "*.cf"`
NB_ERROR=0
for f in $FILES_TO_CHECK
do
  if egrep -q "^[^#]*perms\s*=>\s*mog\([^,]+,\s*[^,]+,\s*['\"]root['\"]\)" ${f}; then
    echo "File $f uses 'root' group, will break tests on non-Linux OSes"
    NB_ERROR=`expr $NB_ERROR + 1`
  fi
done

if [ $NB_ERROR -eq 0 ]; then
  echo "R: $0 Pass"
else
  echo "R: $0 Fail"
fi

exit $NB_ERROR
