#!/usr/bin/env python
#
# Usage: ./ncf_rudder.py path
#

import os.path
import ncf 
import sys
import re
import json

if __name__ == '__main__':

  # Get all generic methods
  generic_methods = ncf.get_all_generic_methods_metadata()
  with open('doc/data.txt', 'w') as outfile:
    json.dump(generic_methods, outfile)

