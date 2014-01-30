#!/usr/bin/env python
#
# Usage: ./ncf_doc.py
#
# This is a Python module to generate documentation from generic methods in ncf

import ncf 

if __name__ == '__main__':

  # Get all generic methods
  generic_methods = ncf.get_all_generic_methods_metadata()

  content = []

  # Generate markdown for each generic method
  for (method_name,generic_method) in generic_methods.iteritems():
    content.append('# '+generic_method["name"])
    content.append('* *Bundle name:* '+method_name)
    content.append('\n## Signature')
    content.append('* ' + method_name + "(" + ", ".join(generic_method["bundle_args"]) + ")")
    content.append('\n## Parameters')
    for args in generic_method["bundle_args"]:
      content.append("* "+args)
    content.append('\n## Classes defined')
    content.append('* '+generic_method["class_prefix"]+"_$("+generic_method["class_parameter"] + ")")
    content.append('')

  # Write generic_methods.md
  result = '\n'.join(content)+"\n"
  outfile = open("doc/generic_methods.md","w")
  outfile.write(result)
  outfile.close()
