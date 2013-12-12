#!/usr/bin/env python
#
# Usage: ./get_promises cfengine_list_name path
###############################################
import os
import sys
import ncf

if len(sys.argv) == 3:
    cfe_name = sys.argv[1]
    path = sys.argv[2]
else:
    print('Incorrect number of arguments')
    sys.exit()

file_list = ncf.get_all_cf_filenames_under_dir(path)

list_string = '","'
print('@%s={ "%s" }') % (cfe_name, list_string.join(file_list))

