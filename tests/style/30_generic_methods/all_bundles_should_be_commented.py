#!/usr/bin/env python

# Check that all generic_methods bundles are preceded with the mandatory comments

import ncf

def check_comments_for_all_generic_methods():
  errors = 0
  filenames = ncf.get_all_generic_methods_filenames()

  for file in filenames:
    content = open(file).read()
    try:
      metadata = ncf.parse_generic_method_metadata(content)
    except Exception as e:
      print "Error in " + file + ": " + e.__str__()
      errors += 1

  if errors == 0:
    print "R: ./30_generic_methods/all_bundles_should_be_commented.py Pass"

  return (errors != 0)

if __name__ == '__main__':
  ret = check_comments_for_all_generic_methods()
  exit(ret)
