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
import xml.etree.ElementTree
import xml.dom.minidom
from xml.etree.ElementTree import ElementTree
from pprint import pprint

# MAIN FUNCTIONS called by command line parsing
###############################################

def convert_all_to_dsc(root_path):
  techniques = ncf.get_all_techniques_metadata(alt_path='/var/rudder/configuration-repository/ncf')['data']['techniques']
  methods = ncf.get_all_generic_methods_metadata(alt_path='/var/rudder/configuration-repository/ncf')['data']['generic_methods']
  ret = 0
  for technique, metadata in techniques.items():
    try:
      write_dsc_technique(metadata, methods)
    except Exception as e:
      sys.stderr.write("Error: Unable to create Rudder Technique files related to ncf Technique "+technique+", skipping... (" + str(e) + ")\n")
      sys.stderr.write(traceback.format_exc())
      ret = 1
      continue
  exit(ret)


def convert_one_to_dsc(destination_path, bundle_name):
  techniques = ncf.get_all_techniques_metadata(alt_path='/var/rudder/configuration-repository/ncf')['data']['techniques']
  methods = ncf.get_all_generic_methods_metadata(alt_path='/var/rudder/configuration-repository/ncf')['data']['generic_methods']
  if bundle_name in techniques.keys():
    try:
      metadata = techniques[bundle_name]
      write_dsc_technique(metadata, methods)
      update_technique_metadata(metadata)
    except Exception as e:
      sys.stderr.write("Error: Unable to create Rudder Technique files related to ncf Technique "+bundle_name+" (" + str(e) + ")\n")
      sys.stderr.write(traceback.format_exc())
      exit(1)
  else:
    sys.stderr.write("Error: Unable to create Rudder Technique files related to ncf Technique "+bundle_name+", cannot find ncf Technique "+bundle_name + "\n")
    sys.stderr.write(traceback.format_exc())
    exit(1)

def write_dsc_technique(technique_metadata, generic_methods):
  """ write expected_reports.csv file from a technique, to a path """
  content = get_ps1_content(technique_metadata, generic_methods)
  technique_name = technique_metadata["bundle_name"]
  file = codecs.open(os.path.realpath(os.path.join("/var/rudder/configuration-repository/ncf/50_techniques", technique_name + ".ps1" )), "w", encoding="utf-8")
  file.write(content)
  file.close()

def bundle_name_to_dsc(bundle_name):
  """Transform a ncf bundle name (lowercase and underscore) to dsc parameter name (CamelCase and dash)"""
  return "-".join([ part.capitalize() for part in bundle_name.split("_") ])

def param_name_to_dsc(param_name):
  """Transform a ncf parameter name (lowercase and underscore) to dsc parameter name (CamelCase)"""
  return "".join([ part.capitalize() for part in param_name.split("_") ])

def get_ps1_content(technique_metadata, generic_methods):
  
  content = []
  content.append("function "+ technique_metadata["bundle_name"] +" {")
  content.append("  [CmdletBinding()]")
  content.append("  param (")
  content.append("      [parameter(Mandatory=$true)]")
  content.append("      [string]$reportId,")
  content.append("      [parameter(Mandatory=$true)]")
  content.append("      [string]$techniqueName,")
  content.append("      [switch]$auditOnly")
  content.append("  )")
  content.append("")
  content.append("  $local_classes = New-ClassContext")
  content.append("")

  generic_params = " -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly)"
  for method_call in technique_metadata["method_calls"]:
   
    method = generic_methods[method_call["method_name"]]

    # Transform ncf params to dsc  
    params = [ param_name_to_dsc(param) for param in method["bundle_args"] ]

    escaped_args = [ arg.replace('"','\\"') for arg in method_call["args"] ] 

    method_params = " ".join( [ "-"+params[ind]+" \""+value+"\"" for ind, value in enumerate(escaped_args) ])

    # N/A Report
    componentName = method["name"]
    componentKey  = escaped_args[method["class_parameter_id"]-1]
    na_report = "_rudder_common_report_na -componentName \"" + componentName+"\" -componentKey \""+componentKey+"\" -message \"Not applicable\"" + generic_params
           
    if method["dsc_support"]:

      dsc_bundle_name = bundle_name_to_dsc(method_call["method_name"])
 
      call = "$local_classes = Merge-ClassContext $local_classes $(" + dsc_bundle_name + " " + method_params + generic_params
      # Do we need to check class on the agent ?
      if method_call['class_context'] != "any":
       
        # Apply method depending on class available or not
        content.append("  $class = \"" + method_call['class_context'] + "\"")
        content.append("  if (Evaluate-Class $class $local_classes $system_classes) {")
        content.append("    " + call)

        content.append("  } else {")
        content.append("    " + na_report)
        content.append("  }")
      else:
        content.append("  "+call)
    else:
      content.append("  "+ na_report)
      
    content.append("")
  content.append("}")
 

  return "\n".join(content) + "\n"

def update_technique_metadata(technique_metadata):
  def prettify(elem):
    rough_string = xml.etree.ElementTree.tostring(elem, 'utf-8')
    reparsed = xml.dom.minidom.parseString(rough_string)
    res = '\n'.join([line for line in reparsed.toprettyxml(indent='  ').split('\n') if line.strip()])
    return res
  bundle_name = technique_metadata["bundle_name"]
  metadata_file = '/var/rudder/configuration-repository/techniques/ncf_techniques/'+bundle_name+"/1.0/metadata.xml"
  metadata_tree = (xml.etree.ElementTree.parse(metadata_file)).getroot()
  agent_element = metadata_tree.find("./AGENT[@type='dsc']")
  if agent_element is None:
    agent_element=xml.etree.ElementTree.fromstring('<AGENT type="dsc"></AGENT>')
    metadata_tree.append(agent_element)

  bundles_meta = "<BUNDLES><NAME>"+bundle_name+"</NAME></BUNDLES>"
  bundles = xml.etree.ElementTree.fromstring(bundles_meta)
  files_meta='<FILES><FILE name="RUDDER_CONFIGURATION_REPOSITORY/ncf/50_techniques/'+bundle_name+'.ps1"><INCLUDED>true</INCLUDED></FILE></FILES>'
  files = xml.etree.ElementTree.fromstring(files_meta)

  agent_element.clear()
  agent_element.set("type", "dsc")
  agent_element.append(bundles)
  agent_element.append(files)

  file = codecs.open(os.path.realpath(metadata_file), "w", encoding="utf-8")
  file.write(prettify(metadata_tree))
  file.close()

def usage():
  sys.stderr.write("Can't parse parameters\n")
  print("Usage: ncf_dsc <command> [arguments]")
  print("Available commands:")
  print(" - convert_all_to_dsc <destination path>")
  print(" - convert_one_to_dsc <destination path> <bundle_name>")


if __name__ == '__main__':

  if len(sys.argv) <= 1:
    usage()
    exit(1)

  if sys.argv[1] == "convert_all_to_dsc":
    convert_all_to_dsc(sys.argv[2])
  elif sys.argv[1] == "convert_one_to_dsc":
    convert_one_to_dsc(sys.argv[2], sys.argv[3])
  else:
    usage()
    exit(1)
