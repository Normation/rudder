#!/usr/bin/python
# -*- coding: utf-8 -*-

# Test if the documentation fields contain unescaped dollar characters that would break pdflatex

import ncf
import re
import sys

from pprint import pprint

if __name__ == '__main__':

  # Get all generic methods
  generic_methods = ncf.get_all_generic_methods_metadata()["data"]["generic_methods"]
  test_file = sys.argv[0]
  
  check_backquotes = re.compile('[^\`]*\$[^\`]*')
  errors = 0
  
  for name, method in generic_methods.items():
    if "documentation" in method:
      if check_backquotes.match(method["documentation"]):
        print("Test "+name+" has non escaped $ in its documentation")
        errors += 1
  
  if errors == 0:
    print("R: "+test_file+" Pass")
  else:
    print("R: "+test_file+" FAIL")
