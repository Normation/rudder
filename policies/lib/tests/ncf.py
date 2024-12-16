# -*- coding: utf-8 -*-
# This is a Python module containing functions to parse and analyze ncf components

# This module is designed to run on the latest major versions of the most popular
# server OSes (Debian, Red Hat/CentOS, Ubuntu, SLES, ...)
# At the time of writing (November 2013) these are Debian 7, Red Hat/CentOS 6,
# Ubuntu 12.04 LTS, SLES 11, ...
# The version of Python in all of these is >= 2.6, which is therefore what this
# module must support

import re
import subprocess
import json
import os.path
import shutil
import sys
import os
import codecs
import uuid
from pprint import pprint

# Verbose output
VERBOSE = 0
CFPROMISES_PATH="/opt/rudder/bin/cf-promises"

dirs = [ "10_ncf_internals", "20_cfe_basics", "30_generic_methods", "40_it_ops_knowledge", "50_techniques", "60_services", "ncf-hooks.d" ]

tags = {}
common_tags            = [ "name", "description", "parameter", "bundle_name", "bundle_args"]
tags["generic_method"] = [ "documentation", "class_prefix", "class_parameter", "class_parameter_id", "deprecated", "agent_requirements", "parameter_constraint", "parameter_type", "action", "rename", "parameter_rename" ]
[ value.extend(common_tags) for (k,value) in tags.items() ]

optionnal_tags = {}
optionnal_tags["generic_method"] = [ "deprecated", "documentation", "parameter_constraint", "parameter_type", "agent_requirements", "action", "rename", "parameter_rename" ]
multiline_tags                   = [ "description", "documentation", "deprecated" ]

class NcfError(Exception):
  def __init__(self, message, details="", cause=None):
    self.message = message
    self.details = details
    # try to get details from inner cause
    try:
      # Will not add to details if cause is None or message is None
      self.details += " caused by : " + cause.message
      # Will not add to details if details is None
      self.details += "\n" + cause.details
    except:
      # We got an error while extending error details, just ignore it and keep current value
      pass

  def __str__(self):
    return repr(self.message)


def format_errors(error_list):
  formated_errors = []
  for error in error_list:
    sys.stderr.write("ERROR: " + error.message + "\n")
    sys.stderr.write(error.details + "\n")
    formated_errors.append( { "message": error.message, "details": error.details } )
  sys.stderr.flush()
  return formated_errors


def get_root_dir():
  return os.path.realpath(os.path.dirname(__file__) + "/../")


# This method emulates the behavior of subprocess check_output method.
# We aim to be compatible with Python 2.6, thus this method does not exist
# yet in subprocess.
def check_output(command, env = {}):
  command_env = dict(env)
  if VERBOSE == 1:
    sys.stderr.write("VERBOSE: About to run command '" + " ".join(command) + "'\n")
  command_env["PATH"] = os.environ['PATH']
  process = subprocess.Popen(command, shell=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=None, env=command_env)
  output, error = process.communicate()
  lines = output.decode("UTF-8", "ignore").split("\n")
  for index, line in enumerate(lines):
    if line.startswith("{"):
      output = "\n".join(lines[index:])
      break;
  error = error.decode("UTF-8", "ignore")
  retcode = process.poll()
  if retcode == 0:
    sys.stderr.write(error)
  else:
    if VERBOSE == 1:
      sys.stderr.write("VERBOSE: Exception triggered, Command returned error code " + str(retcode) + "\n")
    raise NcfError("Error while running '" + " ".join(command) +"' command.", error)

  if VERBOSE == 1:
    sys.stderr.write("VERBOSE: Command output: '" + output + "'" + "\n")
  return output


def get_all_generic_methods_filenames(alt_path=None):
  result = []
  if alt_path is None:
    filelist1 = get_all_generic_methods_filenames_in_dir(get_root_dir() + "/tree/30_generic_methods")
    filelist2 = get_all_generic_methods_filenames_in_dir("/var/rudder/configuration-repository/ncf/30_generic_methods")
    result = filelist1 + filelist2
  else:
    result = get_all_generic_methods_filenames_in_dir(alt_path)

  return result

excluded_dirs = [ "applications", "fileConfiguration", "fileDistribution", "jobScheduling", "systemSettings", "system" ]

def get_all_generic_methods_filenames_in_dir(parent_dir):
  filenames = []
  filenames_add = filenames.append
  for root, dirs, files in os.walk(parent_dir):
    for dir in dirs:
      if dir not in excluded_dirs:
        filenames = filenames + get_all_generic_methods_filenames_in_dir(os.path.join(parent_dir,dir))

    for file in files:
      if  not file.startswith("_") and file.endswith(".cf"):
        filenames.append(os.path.join(root, file))
  return filenames


def parse_generic_method_metadata(content):
  res = {}
  warnings = []
  parameters = []
  param_names = set()
  param_constraints = {}
  param_types = {}
  param_rename = []
  default_constraint = {
    "allow_whitespace_string" : False
  , "allow_empty_string" : False
  , "max_length" : 16384
  }

  multiline = False
  previous_tag = None
  match_line = ""

  for line in content.splitlines():
    # line should already be unicode
    #unicodeLine = unicode(line,"UTF-8") #line.decode('unicode-escape')

    # Parse metadata tag line
    match = re.match("^\s*#\s*@(\w+)\s*(([a-zA-Z0-9_]+)?\s+(.*?)|.*?)\s*$", line, flags=re.UNICODE)
    if match :
      tag = match.group(1)
      # Check if we are a valid tag
      if tag in tags["generic_method"]:
        # tag "parameter" may be multi-valued
        if tag == "parameter":
          param_name = match.group(3)
          parameters.append({'name': param_name, 'description': match.group(4)})
          param_names.add(param_name)
        elif tag == "parameter_constraint":
          constraint = json.loads("{" + match.group(4).replace('\\', '\\\\') + "}")
          # extend default_constraint if it was not already defined)
          param_constraints.setdefault(match.group(3), default_constraint.copy()).update(constraint)
        elif tag == "parameter_type":
          param_type = match.group(4)
          param_types[match.group(3)] = param_type
        elif tag == "parameter_rename":
          old_name = match.group(3)
          new_name = match.group(4)
          param_rename.append( { "new": new_name, "old" : old_name } )
          res['parameter_rename'] = param_rename
        else:
          res[tag] = match.group(2)
        previous_tag = tag
        continue

    # Parse line without tag, if previous tag was a multiline tag
    if previous_tag is not None and previous_tag in multiline_tags:
      match = re.match("^\s*# ?(.*)$", line, flags=re.UNICODE)
      if match:
        res[previous_tag] += "\n"+match.group(1)
        continue
      else:
        previous_tag = None

    # manage multiline bundle definition
    if multiline:
      match_line += line
    else:
      match_line = line
    if re.match("[^#]*bundle\s+agent\s+(\w+)\s*\([^)]*$", match_line, flags=re.UNICODE|re.MULTILINE|re.DOTALL):
      multiline = True

    # read a complete bundle definition
    match = re.match("[^#]*bundle\s+agent\s+(\w+)\s*(\(([^)]*)\))?\s*\{?\s*$", match_line, flags=re.UNICODE|re.MULTILINE|re.DOTALL)
    if match:
      multiline = False
      res['bundle_name'] = match.group(1)
      res['bundle_args'] = []

      if match.group(3) is not None and len(match.group(3)):
        res['bundle_args'] += [x.strip() for x in match.group(3).split(',')]

      # Any tags should come before the "bundle agent" declaration
      break

  # The tag "class_parameter_id" is a magic tag, it's value is built from class_parameter and the list of args
  if "class_parameter_id" in tags["generic_method"]:
    try:
      res['class_parameter_id'] = res['bundle_args'].index(res['class_parameter'])+1
    except:
      res['class_parameter_id'] = 0
      name = res['bundle_name'] if 'bundle_name' in res else "unknown"
      raise NcfError("The class_parameter name \"" + res['class_parameter'] + "\" does not seem to match any of the bundle's parameters in " + name)

  # Check that we don't have a constraint that is defined on a non existing parameter:
  wrong_constraint_names = set(param_constraints.keys()) - param_names
  if len(wrong_constraint_names) > 0:
    warning_message = "In technique '' defining constraint on non existing parameters: "+ ", ".join(wrong_constraint_names)
    print(warning_message)
    warnings.append(warning_message)

  # Check that we don't have a type that is defined on a non existing parameter:
  wrong_type_names = set(param_types.keys()) - param_names
  if len(wrong_type_names) > 0:
    warning_message = "In technique '' defining type on non existing parameters: "+ ", ".join(wrong_type_names)
    print(warning_message)
    warnings.append(warning_message)

  # If we found any parameters, store them in the res object
  if len(parameters) > 0:
    for param in parameters:
      parameter_name = param["name"]
      constraints = param_constraints.get(param["name"], default_constraint)
      param_type = param_types.get(param["name"], "string")
      param["constraints"] = constraints
      param["type"] = param_type

  res['parameter'] = parameters

  # Remove trailing line breaks
  for tag in multiline_tags:
    if tag in res:
      res[tag] = res[tag].strip('\n\r')

  all_tags = tags["generic_method"]
  expected_tags = [ tag for tag in all_tags if not tag in optionnal_tags["generic_method"]]
  if not set(res.keys()).issuperset(set(expected_tags)):
    missing_keys = [mkey for mkey in expected_tags if mkey not in set(res.keys())]
    name = res['bundle_name'] if 'bundle_name' in res else "unknown"
    raise NcfError("One or more metadata tags not found before the bundle agent declaration (" + ", ".join(missing_keys) + ") in " + name)

  result = { "result" : res, "warnings" : warnings }
  return result


def get_all_generic_methods_metadata(alt_path=None):
  all_metadata = {}

  filenames = get_all_generic_methods_filenames(alt_path)
  errors = []
  warnings = []

  for file in filenames:
    with codecs.open(file, encoding="utf-8") as fd:
      content = fd.read()
    try:
      result = parse_generic_method_metadata(content)
      metadata = result["result"]
      warnings.extend(result["warnings"])
      all_metadata[metadata['bundle_name']] = metadata
    except NcfError as e:
      error = NcfError("Could not parse generic method in '" + file + "'", cause=e )
      errors.append(error)
      continue # skip this file, it doesn't have the right tags in - yuk!

  return { "data": { "generic_methods" : all_metadata }, "errors": format_errors(errors), "warnings": warnings }

