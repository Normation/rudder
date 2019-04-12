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
import codecs
import traceback
from pprint import pprint

# MAIN FUNCTIONS called by command line parsing
###############################################

def write_all_techniques_for_rudder(root_path):
  write_category_xml(root_path)
  techniques = ncf.get_all_techniques_metadata(alt_path='/var/rudder/configuration-repository/ncf')['data']['techniques']
  ret = 0
  for technique, metadata in techniques.items():
    try:
      write_technique_for_rudder(root_path, metadata)
    except Exception as e:
      sys.stderr.write("Error: Unable to create Rudder Technique files related to ncf Technique "+technique+", skipping... (" + str(e) + ")\n")
      sys.stderr.write(traceback.format_exc())
      ret = 1
      continue
  exit(ret)


def write_one_technique_for_rudder(destination_path, bundle_name):
  write_category_xml(destination_path)
  techniques = ncf.get_all_techniques_metadata(alt_path='/var/rudder/configuration-repository/ncf')['data']['techniques']
  if bundle_name in techniques.keys():
    try:
      metadata = techniques[bundle_name]
      write_technique_for_rudder(destination_path, metadata)
    except Exception as e:
      sys.stderr.write("Error: Unable to create Rudder Technique files related to ncf Technique "+bundle_name+" (" + str(e) + ")\n")
      sys.stderr.write(traceback.format_exc())
      exit(1)
  else:
    sys.stderr.write("Error: Unable to create Rudder Technique files related to ncf Technique "+bundle_name+", cannot find ncf Technique "+bundle_name + "\n")
    sys.stderr.write(traceback.format_exc())
    exit(1)

# OTHER FUNCTIONS
#################

def get_category_xml():
  """Create a category.xml content to be inserted in the ncf root directory"""

  content = []
  content.append('<xml>')
  content.append('  <name>User Techniques</name>')
  content.append('  <description>')
  content.append('    Techniques created using the Technique editor.')
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
    file = codecs.open(os.path.realpath(category_xml_file), "w", encoding="utf-8")
    file.write(get_category_xml())
    file.close()
  else:
    print("INFO: The " + category_xml_file + " file already exists. Not updating.")


def write_technique_for_rudder(root_path, technique):
  """ From a technique, generate all files needed for Rudder in specified path"""

  path = get_path_for_technique(root_path,technique)
  if not os.path.exists(path):
    os.makedirs(path)
  # We don't need to create rudder_reporting.st if all class context are any
  generic_methods = ncf.get_all_generic_methods_metadata(alt_path='/var/rudder/configuration-repository/ncf')['data']['generic_methods']
  include_rudder_reporting = not all(method_call['class_context'] == 'any' and 
                                     "cfengine-community" in generic_methods[method_call['method_name']]["agent_support"] for method_call in technique["method_calls"])
  if include_rudder_reporting:
    write_rudder_reporting_file(path,technique)

def write_rudder_reporting_file(path,technique):
  """ write rudder_reporting.st file from a technique, to a path """
  file = codecs.open(os.path.realpath(os.path.join(path, "rudder_reporting.cf")), "w", encoding="utf-8")
  content = generate_rudder_reporting(technique)
  file.write(content)
  file.close()

def get_path_for_technique(root_path, technique_metadata):
  """ Generate path where file about a technique needs to be created"""
  return os.path.join(root_path, technique_metadata['bundle_name'], technique_metadata['version'])

def get_logger_call(message, class_prefix, class_parameter):
  return '"dummy_report" usebundle => log_rudder("' + message + '", "' + class_parameter + '", "' + class_prefix +'", "' + class_prefix +'", @{args})'.replace("&", "\\&")


def generate_rudder_reporting(technique):
  """Generate complementary reporting needed for Rudder in rudder_reporting.st file"""
  # Get all generic methods
  generic_methods = ncf.get_all_generic_methods_metadata(alt_path='/var/rudder/configuration-repository/ncf')['data']['generic_methods']

  args = ""
  bundle_param = ""
  if len(technique["parameter"]) > 0:
    bundle_param = "("+", ".join([ncf.canonify(param["name"]) for param in technique["parameter"] ])+")"
    args = ", ".join('"${' +  ncf.canonify(param["name"]) + '}"' for param in technique["parameter"] )

  bundle_name = technique['bundle_name']+'_rudder_reporting'

  content = []
  content.append('bundle agent '+ technique['bundle_name']+'_rudder_reporting'+ bundle_param)
  content.append('{')
  content.append('  vars:')
  content.append('    "args"               slist => { '+ args +' };')
  content.append('    "report_param"      string => join("_", args);')
  content.append('    "full_class_prefix" string => canonify("' + bundle_name + '_${report_param}");')
  content.append('    "class_prefix"      string => string_head("${full_class_prefix}", "1000");')
  content.append('')
  content.append('  methods:')

  report_unique_id = 0

  for method_call in technique["method_calls"]:

    method_name = method_call['method_name']
    if method_call['method_name'].startswith("_"):
      continue
    generic_method = generic_methods[method_name]

    key_value = ncf.get_key_value(method_call, generic_method)

    class_prefix = '${class_prefix}_'+ncf.get_class_prefix(key_value, generic_method)
    method_reporting = '"dummy_report_' + str(report_unique_id) + '" usebundle => ' + ncf.generate_reporting_context(generic_method, method_call) 
    report_unique_id += 1
    class_parameter  = ncf.generate_reporting_class_parameter(generic_method, method_call)

    if not "cfengine-community" in generic_method["agent_support"]:

      message = "'"+generic_method["name"]+"' method is not available on cfengine based agent, skip"
      logger_call = get_logger_call(message, class_prefix, class_parameter)

      content.append('    any::')
      content.append('    "dummy_report" usebundle => _classes_noop("'+class_prefix+'");')
      content.append('    ' + method_reporting + ';')
      content.append('    ' + logger_call+';')

    elif method_call['class_context'] != "any":
      # escape double quote in key value
      regex_quote = re.compile(r'(?<!\\)"', flags=re.UNICODE )
      escaped_key_value = regex_quote.sub('\\"', key_value)

      message = generic_method['name'] + ' ' + escaped_key_value + ' if ' + method_call['class_context']
      logger_rudder_call = get_logger_call(message, class_prefix, class_parameter)

      # Always add an empty line for readability
      content.append('')

      if not "$" in method_call['class_context']:
        content.append('    !('+method_call['class_context']+')::')
        content.append('      "dummy_report" usebundle => _classes_noop("'+class_prefix+'");')
        content.append('      ' + method_reporting + ';')
        content.append('      ' + logger_rudder_call + ';')

      else:
        class_context = ncf.canonify_class_context(method_call['class_context'])

        content.append('      "dummy_report" usebundle => _classes_noop("'+class_prefix+'"),')
        content.append('                    ifvarclass => concat("!('+class_context+')");')
        content.append('      ' + method_reporting + ',')
        content.append('                    ifvarclass => concat("!('+class_context+')");')
        content.append('      ' + logger_rudder_call + ',')
        content.append('                    ifvarclass => concat("!('+class_context+')");')

  content.append('}')

  # Join all lines with \n to get a pretty CFEngine file
  result =  '\n'.join(content)+"\n"

  return result


def usage():
  sys.stderr.write("Can't parse parameters\n")
  print("Usage: ncf_rudder <command> [arguments]")
  print("Available commands:")
  print(" - rudderify_techniques <destination path>")
  print(" - rudderify_technique <destination path> <bundle_name>")


if __name__ == '__main__':

  if len(sys.argv) <= 1:
    usage()
    exit(1)

  if sys.argv[1] == "rudderify_techniques":
    write_all_techniques_for_rudder(sys.argv[2])
  elif sys.argv[1] == "rudderify_technique":
    write_one_technique_for_rudder(sys.argv[2],sys.argv[3])
  else:
    usage()
    exit(1)
