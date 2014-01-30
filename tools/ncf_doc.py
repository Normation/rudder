#!/usr/bin/env python
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

  content.append("Title: Generic methods")
  content.append("slugs: generic-methods")
  content.append("Author: Normation")

  for category in sorted(categories.iterkeys()):
     content.append("* ["+category.title()+"](#"+category+")")
  for category in sorted(categories.iterkeys()):
    content.append('<a name="'+category+'"></a><br/><br/>')
    content.append('\n# '+category.title())
 
  # Generate markdown for each generic method
    for generic_method in categories[category]:
      content.append('\n## '+generic_method["name"])
      content.append('* *Bundle name:* '+method_name)
      content.append('\n### Signature')
      content.append('* ' + method_name + "(" + ", ".join(generic_method["bundle_args"]) + ")")
      content.append('\n### Parameters')
      for args in generic_method["bundle_args"]:
        content.append("* "+args)
      content.append('\n### Classes defined')
      content.append('* '+generic_method["class_prefix"]+"_$("+generic_method["class_parameter"] + ")")
      content.append('')

  # Write generic_methods.md
  result = '\n'.join(content)+"\n"
  outfile = open("doc/generic_methods.md","w")
  outfile.write(result)
  outfile.close()
