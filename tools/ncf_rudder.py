#!/usr/bin/env python
# -*- coding: utf-8 -*-
#
# Usage: ./ncf_rudder.py path
#
# This is a Python module containing functions to generate technique for Rudder from ncf techniques
#
# This module is designed to run on the latest major versions of the most popular
# server OSes (Debian, Red Hat/CentOS, Ubuntu, SLES, ...)
# At the time of writing (November 2013) these are Debian 7, Red Hat/CentOS 6,
# Ubuntu 12.04 LTS, SLES 11, ...
# The version of Python in all of these is >= 2.6, which is therefore what this
# module must support

import os.path
import ncf 
import sys
import re

def write_all_techniques_for_rudder(root_path):
  write_category_xml(root_path)
  techniques = ncf.get_all_techniques_metadata(alt_path='/var/rudder/configuration-repository/ncf')
  for technique, metadata in techniques.iteritems():
    try:
      write_technique_for_rudder(root_path, metadata)
    except Exception, e:
     print("Error: Unable to create Rudder Technique files related to ncf Technique "+technique+", skipping... (" + str(e) + ")")
     continue

def write_one_technique_for_rudder(destination_path, bundle_name):
  write_category_xml(destination_path)
  techniques = ncf.get_all_techniques_metadata(alt_path='/var/rudder/configuration-repository/ncf')
  if bundle_name in techniques.keys():
    try:
      metadata = techniques[bundle_name]
      write_technique_for_rudder(destination_path, metadata)
    except Exception, e:
     print("Error: Unable to create Rudder Technique files related to ncf Technique "+bundle_name+" (" + str(e) + ")")
  else:
     print("Error: Unable to create Rudder Technique files related to ncf Technique "+bundle_name+", cannot find ncf Technique "+bundle_name)

def get_category_xml():
  """Create a category.xml content to be inserted in the ncf root directory"""

  content = []
  content.append('<xml>')
  content.append('  <name>Meta Techniques</name>')
  content.append('  <description>')
  content.append('    Meta Techniques created using the ncf framework.')
  content.append('  </description>')
  content.append('</xml>')

  # Join all lines with \n to get a pretty xml
  result = '\n'.join(content)+"\n"
  return result

def write_category_xml(path):
  """Write the category.xml file to make Rudder acknowledge this directory as a Technique section"""

  # First, make sure that the directories are all here
  if not os.path.exists(path):
    os.makedirs(path)

  # Then, skip the creation of the category.xml file if already present
  category_xml_file = os.path.join(path, "category.xml")

  if not os.path.exists(category_xml_file):
    file = open(os.path.realpath(category_xml_file),"w")
    file.write(get_category_xml())
    file.close()
  else:
    print "INFO: The " + category_xml_file + " file already exists. Not updating."

def write_technique_for_rudder(root_path, technique):
  """ From a technique, generate all files needed for Rudder in specified path"""

  path = get_path_for_technique(root_path,technique)
  if not os.path.exists(path):
    os.makedirs(path)
  # We don't need to create rudder_reporting.st if all class context are any
  include_rudder_reporting = not all(method_call['class_context'] == 'any' for method_call in technique["method_calls"])
  write_xml_metadata_file(path,technique,include_rudder_reporting)
  write_expected_reports_file(path,technique)
  if include_rudder_reporting:
    write_rudder_reporting_file(path,technique)

def write_xml_metadata_file(path, technique, include_rudder_reporting = False):
  """ write metadata.xml file from a technique, to a path """
  file = open(os.path.realpath(os.path.join(path, "metadata.xml")),"w")
  content = get_technique_metadata_xml(technique, include_rudder_reporting)
  file.write(content)
  file.close()

def write_expected_reports_file(path,technique):
  """ write expected_reports.csv file from a technique, to a path """
  file = open(os.path.realpath(os.path.join(path, "expected_reports.csv")),"w")
  content = get_technique_expected_reports(technique)
  file.write(content)
  file.close()

def write_rudder_reporting_file(path,technique):
  """ write rudder_reporting.st file from a technique, to a path """
  file = open(os.path.realpath(os.path.join(path, "rudder_reporting.st")),"w")
  content = generate_rudder_reporting(technique)
  file.write(content)
  file.close()

def get_technique_metadata_xml(technique_metadata, include_rudder_reporting = False):
  """Get metadata xml for a technique as string"""

  # Get all generic methods
  generic_methods = ncf.get_all_generic_methods_metadata(alt_path='/var/rudder/configuration-repository/ncf')

  content = []
  content.append('<TECHNIQUE name="'+technique_metadata['name']+'">')
  content.append('  <DESCRIPTION>'+technique_metadata['description']+'</DESCRIPTION>')
  content.append('  <BUNDLES>')
  content.append('    <NAME>'+ technique_metadata['bundle_name'] + '</NAME>')
  if include_rudder_reporting:
    content.append('    <NAME>'+ technique_metadata['bundle_name'] + '_rudder_reporting</NAME>')
  content.append('  </BUNDLES>')

  if include_rudder_reporting:
    content.append('  <TMLS>')
    content.append('    <TML name="rudder_reporting"/>')
    content.append('  </TMLS>')

  content.append('  <SECTIONS>')

  method_calls = technique_metadata["method_calls"]  

  # Get all method call, with no duplicate values
  methods_name = set()
  for method_call in method_calls:
    # Expected reports for Rudder should not include any "meta" bundle calls (any beginning with _)
    if method_call['method_name'].startswith("_"):
      continue

    method_name = methods_name.add(method_call['method_name'])

  # For each method used, create a section containing all calls to that method
  section_list = []
  for method_name in methods_name:

    try:
      generic_method = generic_methods[method_name]
    except Exception, e:
      print "Error: The method '" + method_name + "' does not exist. Aborting Technique creation..."

    # Filter all method calls to get only those about that method
    filter_method_calls = [x for x in method_calls if x["method_name"] == method_name]
    # Generare xml for that section
    section = generate_section_xml(filter_method_calls, generic_method)
    section_list.extend(section)

  content.extend(section_list)
  content.append('  </SECTIONS>')
  content.append('</TECHNIQUE>')

  # Join all lines with \n to get a pretty xml
  result =  '\n'.join(content)+"\n"

  return result

def generate_section_xml(method_calls, generic_method):
  """ Generate xml section about a method used by that technique"""
  content = []
  content.append('    <SECTION component="true" multivalued="true" name="'+generic_method["name"]+'">')
  content.append('      <REPORTKEYS>')

  # For each method call, generate a value
  values = []
  for method_call in method_calls:
    # Generate XML for that method call
    value = generate_value_xml(method_call,generic_method)
    values.append(value)

  content.extend(values)
  content.append('      </REPORTKEYS>') 
  content.append('    </SECTION>')
  
  
  return content 
 
def generate_value_xml(method_call,generic_method):
  """Generate xml containing value needed for reporting from a method call"""
  try:
    parameter = method_call["args"][generic_method["class_parameter_id"]-1]
    value = parameter
    
    return "        <VALUE><![CDATA["+value+"]]></VALUE>"
  except:
    raise Exception("Method parameter \"" + generic_method['class_parameter_id'] + "\" is not defined")


def get_technique_expected_reports(technique_metadata):
  """Generates technique expected reports from technique metadata"""
  # Get all generic methods
  generic_methods = ncf.get_all_generic_methods_metadata(alt_path='/var/rudder/configuration-repository/ncf')

  # Content start with a header
  content = ["""# This file contains one line per report expected by Rudder from this technique
# Format: technique_name;;class_prefix_${key};;@@RUDDER_ID@@;;component name;;component key"""]
  
  technique_name = technique_metadata['bundle_name']
  for method_call in technique_metadata["method_calls"]:
    # Expected reports for Rudder should not include any "meta" bundle calls (any beginning with _)
    if method_call['method_name'].startswith("_"):
      continue

    method_name = method_call['method_name']
    generic_method = generic_methods[method_name]

    component = generic_method['name']
    key_value = method_call["args"][generic_method["class_parameter_id"]-1]
    class_prefix = generic_method["class_prefix"]+"_"+key_value

    line = technique_name+";;"+class_prefix+";;@@RUDDER_ID@@;;"+component+";;"+key_value
    
    content.append(line)

  # Join all lines + last line
  result = "\n".join(content)+"\n"
  return result


def get_path_for_technique(root_path, technique_metadata):
  """ Generate path where file about a technique needs to be created"""
  return os.path.join(root_path, technique_metadata['bundle_name'], technique_metadata['version'])

def canonify_expected_reports(expected_reports, dest):

  # Open file containing original expected_reports
  source_file = open(expected_reports)

  # Create destination file
  dest_file = open(dest, 'w')
  
  # Iterate over each line (this does *not* read the whole file into memory)
  for line in source_file:
    # Just output comments as they are
    if re.match("^\s*#", line):
      dest_file.write(line)
      continue

    # Replace the second field with a canonfied version of itself (a la CFEngine)
    fields = line.strip().split(";;")
    fields[1] = re.sub("[^a-zA-Z0-9_]", "_", fields[1])
    dest_file.write(";;".join(fields) + "\n")


def generate_rudder_reporting(technique):
  """Generate complementary reporting needed for Rudder in rudder_reporting.st file"""
  # Get all generic methods
  generic_methods = ncf.get_all_generic_methods_metadata(alt_path='/var/rudder/configuration-repository/ncf')

  content = []
  content.append('bundle agent '+ technique['bundle_name']+'_rudder_reporting')
  content.append('{')
  content.append('  methods:')

  # Handle method calls
  technique_name = technique['bundle_name']
  # Filter calls so we only have those who are not using a any class_context
  filter_calls = [ method_call for method_call in technique["method_calls"] if method_call['class_context'] != "any" ]

  for method_call in filter_calls:

    method_name = method_call['method_name']
    generic_method = generic_methods[method_name]

    key_value = method_call["args"][generic_method["class_parameter_id"]-1]
    key_value_canonified = re.sub("[^\$\{\}\w](?![^{}]+})|\$(?!{)", "_", key_value)


    class_prefix = generic_method["class_prefix"]+"_"+key_value_canonified

    # Always add an empty line for readability
    content.append('')

    if not "$" in method_call['class_context']:
      content.append('    !('+method_call['class_context']+')::')
      content.append('      "dummy_report" usebundle => _classes_noop("'+class_prefix+'");')
      content.append('      "dummy_report" usebundle => logger_rudder("Not applicable", "'+class_prefix+'");')

    else:
      content.append('      "dummy_report" usebundle => _classes_noop("'+class_prefix+'"),')
      content.append('                    ifvarclass => "!('+method_call['class_context']+')";')
      content.append('      "dummy_report" usebundle => logger_rudder("Not applicable", "'+class_prefix+'"),')
      content.append('                    ifvarclass => "!('+method_call['class_context']+')";')




  content.append('}')

  # Join all lines with \n to get a pretty CFEngine file
  result =  '\n'.join(content)+"\n"

  return result

def usage():
  print("Usage: ncf_rudder <command> [arguments]")
  print("Available commands:")
  print(" - canonify_expected_reports <source file> <destination file>")
  print(" - rudderify_techniques <destination path>")
  print(" - rudderify_technique <destination path> <bundle_name>")

if __name__ == '__main__':

  if len(sys.argv) <= 1:
    usage()
    exit(1)

  if sys.argv[1] == "canonify_expected_reports":
    canonify_expected_reports(sys.argv[2], sys.argv[3])
  elif sys.argv[1] == "rudderify_techniques":
    write_all_techniques_for_rudder(sys.argv[2])
  elif sys.argv[1] == "rudderify_technique":
    write_one_technique_for_rudder(sys.argv[2],sys.argv[3])
  else:
    usage()
    exit(1)
