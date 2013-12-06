# This is a Python module containing functions to parse and analyze ncf components

# This module is designed to run on the latest major versions of the most popular
# server OSes (Debian, Red Hat/CentOS, Ubuntu, SLES, ...)
# At the time of writing (November 2013) these are Debian 7, Red Hat/CentOS 6,
# Ubuntu 12.04 LTS, SLES 11, ...
# The version of Python in all of these is >= 2.6, which is therefore what this
# module must support

import os.path
import ncf 
import xml.etree.cElementTree as XML

def generate_all_techniques(root_path): 
  techniques = ncf.get_all_techniques_metadata
  for technique in techniques:
    print("TODO")


def get_technique_metadata_xml(technique_metadata):

  generic_methods = ncf.get_all_generic_methods_metadata()
  root = XML.Element("TECHNIQUE")
  root.set("name", technique_metadata['name'])
  
  description = XML.SubElement(root, "DESCRIPTION")
  description.text = technique_metadata['description']

  bundles = XML.SubElement(root, "BUNDLES")
  bundleName = XML.SubElement(root, "NAME")
  bundleName.text = technique_metadata['bundle_name']

  sections = XML.SubElement(root, "SECTIONS")

  method_calls = technique_metadata["method_calls"]  

  methods_name = set()
  for method_call in method_calls:
    method_name = methods_name.add(method_call['method_name'])

  section_list = []
  for method_name in methods_name:
    generic_method = generic_methods[method_name]
    filter_method_calls = [x for x in method_calls if x["method_name"] == method_name]
    section = generate_section_xml(filter_method_calls, generic_method)
    section_list.append(section)

  sections.extend(section_list)

  return root

def generate_section_xml(method_calls, generic_method):

  def generate_value(method_call):
    return generate_value_xml(method_call, generic_method)

  section = XML.Element("SECTION")
  section.set("component","true")
  section.set("multivalued","true")
  section.set("name", generic_method["name"])
 
  reportKeys = XML.SubElement(section, "REPORTKEYS")
  values = map(generate_value, method_calls)
  reportKeys.extend(values)
  
  return section
 
def generate_value_xml(method_call,generic_method):
  try: 
    parameter = method_call["args"][generic_method["class_parameter_id"]-1]
    value = generic_method["class_prefix"] + "_" + parameter
    
    node = XML.Element("VALUE")
    node.text = value
    return node
  except:
    raise Exception("Method parameter \"" + generic_method['class_parameter_id'] + "\" is not defined")


def get_technique_expected_reports(technique_metadata):
  """Generates technique expected reports from technique metadata"""
  generic_methods = ncf.get_all_generic_methods_metadata()
  content = ["""# This file contains one line per report expected by Rudder from this technique
# Format: technique_name;;class_prefix_${key};;@@RUDDER_ID@@;;component name;;component key"""]
  
  technique_name = technique_metadata['bundle_name']
  for method_call in technique_metadata["method_calls"]:
    method_name = method_call['method_name']
    generic_method = generic_methods[method_name]

    component = generic_method['name']
    key_value = method_call["args"][generic_method["class_parameter_id"]-1]
    class_prefix = generic_method["class_prefix"]+"_"+key_value

    line = technique_name+";;"+class_prefix+";;@@RUDDER_ID@@;;"+component+";;"+key_value
    
    content.append(line)
  return "\n".join(content)+"\n"


def get_path_for_technique(root_path, technique_metadata):
  return os.path.join(root_path, technique_metadata['bundle_name'], technique_metadata['version'])
