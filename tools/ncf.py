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
CFENGINE_PATH="/opt/rudder/bin/cf-promises"

dirs = [ "10_ncf_internals", "20_cfe_basics", "30_generic_methods", "40_it_ops_knowledge", "50_techniques", "60_services", "ncf-hooks.d" ]

tags = {}
common_tags            = [ "name", "description", "parameter", "bundle_name", "bundle_args"]
tags["generic_method"] = [ "documentation", "class_prefix", "class_parameter", "class_parameter_id", "deprecated", "agent_version", "agent_requirements", "parameter_constraint", "parameter_type", "action", "rename" ]
tags["technique"]      = [ "version" ]
[ value.extend(common_tags) for (k,value) in tags.items() ]

optionnal_tags = {}
optionnal_tags["generic_method"] = [ "deprecated", "documentation", "parameter_constraint", "parameter_type", "agent_requirements", "action", "rename" ]
optionnal_tags["technique"]      = [ "parameter" ]
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


def get_all_generic_methods_filenames_in_dir(dir):
  return get_all_cf_filenames_under_dir(dir, False)


def get_all_techniques_filenames(migrate_technique = False):
  basePath = "/var/rudder/configuration-repository"
  if migrate_technique:
    path = os.path.join(basePath,"ncf/50_techniques")
  else:
    path = os.path.join(basePath,"techniques")

  return list(set(get_all_cf_filenames_under_dir(path, not migrate_technique)))

excluded_dirs = [ "applications", "fileConfiguration", "fileDistribution", "jobScheduling", "systemSettings", "system" ]
def get_all_cf_filenames_under_dir(parent_dir, only_technique_cf):
  filenames = []
  filenames_add = filenames.append
  for root, dirs, files in os.walk(parent_dir):
    for dir in dirs:
      if dir not in excluded_dirs:
        filenames = filenames + get_all_cf_filenames_under_dir(os.path.join(parent_dir,dir),only_technique_cf)

    for file in files:
      if only_technique_cf:
        if file == "technique.cf":
          filenames.append(os.path.join(root, file))
      elif  not file.startswith("_") and file.endswith(".cf"):
        filenames.append(os.path.join(root, file))
  return filenames


def parse_technique_metadata(technique_content):
  return parse_bundlefile_metadata(technique_content, "technique")


def parse_generic_method_metadata(technique_content):
  return parse_bundlefile_metadata(technique_content, "generic_method")


def parse_bundlefile_metadata(content, bundle_type):
  res = {}
  warnings = []
  parameters = []
  param_names = set()
  param_constraints = {}
  param_types = {}
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
      if tag in tags[bundle_type]:
        # tag "parameter" may be multi-valued
        if tag == "parameter":
          if bundle_type == "generic_method":
            param_name = match.group(3)
            parameters.append({'name': param_name, 'description': match.group(4)})
            param_names.add(param_name)
          else:
            try:
              parameter = json.loads(match.group(2))
              parameters.append(parameter)
            except:
              print("Error while loading JSON " +match.group(2))
        if tag == "parameter_constraint":
          constraint = json.loads("{" + match.group(4).replace('\\', '\\\\') + "}")
          # extend default_constraint if it was not already defined)
          param_constraints.setdefault(match.group(3), default_constraint.copy()).update(constraint)
        if tag == "parameter_type":
          param_type = match.group(4)
          param_types[match.group(3)] = param_type
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
  if "class_parameter_id" in tags[bundle_type]:
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

  if bundle_type == "generic_method" and not "agent_version" in res:
    res["agent_version"] = ">= 3.6"

  # Remove trailing line breaks
  for tag in multiline_tags:
    if tag in res:
      res[tag] = res[tag].strip('\n\r')

  all_tags = tags[bundle_type]
  expected_tags = [ tag for tag in all_tags if not tag in optionnal_tags[bundle_type]]
  if not set(res.keys()).issuperset(set(expected_tags)):
    missing_keys = [mkey for mkey in expected_tags if mkey not in set(res.keys())]
    name = res['bundle_name'] if 'bundle_name' in res else "unknown"
    raise NcfError("One or more metadata tags not found before the bundle agent declaration (" + ", ".join(missing_keys) + ") in " + name)

  result = { "result" : res, "warnings" : warnings }
  return result


def class_context_and(a, b):
  """Concatenate two CFEngine class contexts, and simplify useless cases"""

  # Filter 'any' class
  contexts = [ context for context in [a,b] if context != "any" ]

  final_contexts = []
  # Add parenthesis if necessary
  if len(contexts) > 1:
    for context in contexts:
      if '.' in context or '&' in context or '|' in context:
        final_contexts.append('(' + context + ')')
      else:
        final_contexts.append(context)
  else:
    final_contexts = contexts

  # If nothing is left, just use the placeholder "any"
  if len(final_contexts) == 0:
    final_contexts.append('any')

  return '.'.join(final_contexts)

def sanitize_cfpromises_string (value):
  """All quotes in json provided by cf-promises are backslashed, so we need to remove all backslash before a quote from all values"""
  return value.replace('\\"', '"').replace("\\'", "'")

def parse_function_call_class_context(function_call):
  """Extract a function call from class context"""
  function_name = function_call['name']
  function_args = [ sanitize_cfpromises_string(function_arg['value']) for function_arg in function_call['arguments']]
  # This is valid for string parameters only should improve for inner function
  return function_name + '(' + ','.join(function_args) + ')'


def parse_technique_methods(technique_file, gen_methods):
  res = []

  # Check file exists
  if not os.path.exists(technique_file):
    raise NcfError("No such file: " + technique_file)

  env = os.environ.copy()
  env['RES_OPTIONS'] = 'attempts:0'
  out = check_output([CFENGINE_PATH, "-pjson", "-f", technique_file], env=env)
  try:
    promises = json.loads(out)
  except Exception as e:
      raise NcfError("An error occured while parsing technique '"+technique_file+"'", cause = e)

  # Sanity check: if more than one bundle, this is a weird file and I'm quitting
  bundle_count = 0
  for bundle in promises['bundles']:
    if bundle['bundleType'] == "agent":
      bundle_count += 1

  if bundle_count > 1:
    raise NcfError("There is not exactly one bundle in " + technique_file + ", aborting")

  # Sanity check: the bundle must be of type agent
  if promises['bundles'][0]['bundleType'] != 'agent':
    raise NcfError("This bundle is not a bundle agent in " + technique_file + ", aborting")

  methods_promises = [promiseType for promiseType in promises['bundles'][0]['promiseTypes'] if promiseType['name']=="methods"]
  methods = []
  if len(methods_promises) >= 1:
    methods = methods_promises[0]['contexts']

  for context in methods:
    class_context = context['name']

    for method in context['promises']:
      method_name = None
      args = None
      promise_class_context = class_context
      ifvarclass_context = None
      # Promiser is used as report component, but in 5.1 we added an unique identifier to identify each generic method to make it more unique
      # (because if promiser are unique and params too, method is not run, cfengine way of life)
      # Format of the identifier is _directiveId_methodIndex, first is given by the variable from report_data, other one is just and int
      promiser = re.sub("_\${report_data\.directive_id}_\d+$", "", method['promiser'])

      for attribute in method['attributes']:
        if attribute['lval'] == 'usebundle':
          if attribute['rval']['type'] == 'functionCall':
            method_name = attribute['rval']['name']
            args = [ sanitize_cfpromises_string(arg['value']) for arg in attribute['rval']['arguments']]
          if attribute['rval']['type'] == 'string':
            method_name = attribute['rval']['value']
        # Extract class context from 'ifvarclass'
        elif attribute['lval'] == 'ifvarclass' or attribute['lval'] == 'if':
          # Simple string get its value
          if attribute['rval']['type'] == 'string':
            ifvarclass_context = attribute['rval']['value']
          # We have a function call here, and need to treat concat case
          if attribute['rval']['type'] == 'functionCall':
            ifvarclass_function = attribute['rval']['name']
            # Function is concat! We use that to handle variable in classes:
            # variables in classes are expanded at runtime, making invalid character in classes
            # We have to canonify variables only, and not the whole if var class
            # as it would replace all the 'invalid' character from the class ( and '.' , not '!', ...)
            # so a class like:
            # Monday.${bundle2.var}.debian.${bundle.var}.linux
            # will be written
            # concat("Monday.",canonify(${bundle2.var}),".debian.",canonify(${bundle.var}),".linux")
            # But the class we really want to extract is:
            # Monday.${bundle2.var}.debian.${bundle.var}.linux
            if ifvarclass_function == 'concat':
              ifvarclass_args = []
              for arg in attribute['rval']['arguments']:
                # simple string get only the value
                if arg['type'] == 'string':
                  ifvarclass_args.append(arg['value'])
                # This a canonify call, extract only the value of the canonify
                elif arg['type'] == 'functionCall' and arg['name'] == 'canonify':
                  ifvarclass_args.append(arg['arguments'][0]['value'])
                # Extract the function call correctly
                else:
                  function_call = parse_function_call_class_context(arg)
                  ifvarclass_args.append(function_call)
              ifvarclass_context = ''.join(ifvarclass_args)
            # Another function call, extract it directly
            else:
              ifvarclass_context = parse_function_call_class_context(attribute['rval'])

      if ifvarclass_context is not None:
        promise_class_context = class_context_and(class_context, ifvarclass_context)

      if not (method_name.startswith("_") or method_name.startswith("log")):
        if promiser == "method_call":
          promiser = gen_methods[method_name]["name"]
        if args:
          res.append({'class_context': promise_class_context, 'component': promiser, 'method_name': method_name, 'args': args})
        else:
          res.append({'class_context': promise_class_context, 'component': promiser, 'method_name': method_name})

  return res

# FUNCTIONS called directly by the API code
###########################################

def get_all_techniques_metadata(include_methods_calls = True, migrate_technique = False):
  methods_data = get_all_generic_methods_metadata()
  methods = methods_data["data"]["generic_methods"]
  all_metadata = {}

  filenames = get_all_techniques_filenames(migrate_technique)
  method_errors = methods_data["errors"]
  warnings = methods_data["warnings"]
  errors = []
  for file in filenames:
    with codecs.open(file, encoding="utf-8") as fd:
      content = fd.read()
    try:
      # path of file is Category/technique_name/technique_version/technique.cf  
      # to get back the category of our technique we need to go up 3 directories
      category = os.path.basename(os.path.dirname(os.path.dirname(os.path.dirname(file))))
      # if we are migrating a technique (5.0 -> 6.0) from configuration-repository/ncf to techniques/ncf_techniques, we need to set the category manually
      if migrate_technique:
        category = "ncf_techniques"
      result = parse_technique_metadata(content)
      metadata = result["result"]
      metadata["category"] = category
      warnings.extend(result["warnings"])

      if include_methods_calls:
        method_calls = parse_technique_methods(file, methods)
        metadata['method_calls'] = method_calls

      all_metadata[metadata['bundle_name']] = metadata

    except NcfError as e:
      bundle_name = os.path.splitext(os.path.basename(file))[0]
      error = NcfError("Could not parse Technique '"+ bundle_name+ "'", cause=e)
      errors.append(error)
      continue # skip this file, it doesn't have the right tags in - yuk!

  return { "data": { "techniques" : all_metadata, "generic_methods" : methods }, "errors": method_errors + format_errors(errors), "warnings": warnings }


def get_agents_support(method, content):
  agents = []
  if os.path.exists("/var/rudder/configuration-repository/dsc/ncf/30_generic_methods/" + method + ".ps1"):
    agents.append("dsc")
  if not re.search(r'\n\s*bundle\s+agent\s+'+method+r'\b[^{]*?\{\s*\}', content, re.DOTALL): # this matches an empty bundle content
    agents.append("cfengine-community")
  return agents

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
      metadata["agent_support"] = get_agents_support(metadata["bundle_name"], content)
      all_metadata[metadata['bundle_name']] = metadata
    except NcfError as e:
      error = NcfError("Could not parse generic method in '" + file + "'", cause=e )
      errors.append(error)
      continue # skip this file, it doesn't have the right tags in - yuk!

  return { "data": { "generic_methods" : all_metadata }, "errors": format_errors(errors), "warnings": warnings }

