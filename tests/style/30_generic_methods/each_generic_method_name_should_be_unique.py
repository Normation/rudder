#!/usr/bin/env python

# Check that each generic_methods bundle's '@name' property is unique

import ncf

def check_comments_for_all_generic_methods():
  errors = 0
  filenames = ncf.get_all_generic_methods_filenames()
  names = []

  for file in filenames:
    content = open(file).read()
    try:
      metadata = ncf.parse_generic_method_metadata(content)
    except Exception as e:
      print "Error in " + file + ": " + e.__str__()

    if metadata['name'] in names:
      print "Name '" + metadata['name'] + "' already used by another generic_method (found in file " + file + ")"
      errors += 1
    else:
      names.append(metadata['name'])

  if errors == 0:
    print "R: ./30_generic_methods/each_generic_method_name_should_be_unique.py Pass"

  return (errors != 0)

if __name__ == '__main__':
  ret = check_comments_for_all_generic_methods()
  exit(ret)
