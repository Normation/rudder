#!/usr/bin/env python
# -*- coding: utf-8 -*-
#
# Usage: ./ncf_doc.py
#
# This is a Python module to generate documentation from generic methods in ncf

import ncf 

if __name__ == '__main__':

  # Get all generic methods
  generic_methods = ncf.get_all_generic_methods_metadata()
  
  
  categories = {}
  for method_name in sorted(generic_methods.iterkeys()):
    category_name = method_name.split('_',1)[0]
    generic_method = generic_methods[method_name]
    if (category_name in categories):
      categories[category_name].append(generic_method)
    else:
      categories[category_name] = [generic_method]
    


  content = []

  content.append("Title: Reference")
  content.append("slugs: reference")
  content.append("Author: Normation")

  for category in sorted(categories.iterkeys()):
    content.append("* ["+category.title()+"](#"+category+")")
    for generic_method in categories[category]:
      name = generic_method["bundle_name"]
      content.append("    * ["+name+"](#"+name+")")
  for category in sorted(categories.iterkeys()):
    content.append('<a class="anchor" name="'+category+'"></a>')
    content.append('\n## '+category.title())
 
    # Generate markdown for each generic method
    for generic_method in categories[category]:
      bundle_name = generic_method["bundle_name"]
      content.append('<a class="anchor" name="'+generic_method["bundle_name"]+'"></a>')
      content.append('\n### '+ bundle_name)
      content.append(generic_method["description"])
      content.append('\n#### Signature')
      content.append('    :::cfengine3')
      content.append('    bundle agent ' + bundle_name + "(" + ", ".join(generic_method["bundle_args"]) + ")")
      content.append('\n#### Parameters')
      for parameter in generic_method["parameter"]:
        content.append("* **" + parameter['name'] + "**: " + parameter['description'])
      content.append('\n#### Classes defined')
      content.append('    :::perl')
      content.append('    '+generic_method["class_prefix"]+"_${"+generic_method["class_parameter"] + "}_{kept, repaired, not_ok, reached}")
      content.append('<hr/>')

  # Write generic_methods.md
  result = '\n'.join(content)+"\n"
  outfile = open("doc/generic_methods.md","w")
  outfile.write(result)
  outfile.close()
