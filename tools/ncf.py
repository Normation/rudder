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

# Additionnal path to look for cf-promises
additional_path = ["/opt/rudder/bin","/usr/sbin","/usr/local"]

# Verbose output
VERBOSE = 0

dirs = [ "10_ncf_internals", "20_cfe_basics", "30_generic_methods", "40_it_ops_knowledge", "50_techniques", "60_services", "ncf-hooks.d" ]

tags = {}
tags["common"] = ["bundle_name", "bundle_args"]
tags["generic_method"] = ["name", "description", "parameter", "class_prefix", "class_parameter", "class_parameter_id"]
tags["technique"] = ["name", "description", "version"]

multiline_tags = [ "description" ]

class NcfError(Exception):
  def __init__(self, message, details="", cause=None):
    self.message = message
    self.details = details
    if cause is not None:
      self.details += " caused by : " + cause.message + "\n" + cause.details

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
  if len(additional_path) == 0:
    env_path = os.environ['PATH']
  else:
    cfpromises_path = ":".join(additional_path)
    env_path = cfpromises_path + ":" + os.environ['PATH']
    command_env["PATH"] = env_path
  process = subprocess.Popen(command, shell=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, env=command_env)
  output, error = process.communicate()
  retcode = process.poll()
  if retcode == 0:
    sys.stderr.write(error)
  else:
    if VERBOSE == 1:
      sys.stderr.write("VERBOSE: Exception triggered, Command returned error code " + retcode + "\n")
    raise NcfError("Error while running post-hook command " + str(command), error)

  if VERBOSE == 1:
    sys.stderr.write("VERBOSE: Command output: '" + output + "'" + "\n")
  return output


def get_all_generic_methods_filenames(alt_path = ''):
  result = []
  filelist1 = get_all_generic_methods_filenames_in_dir(get_root_dir() + "/tree/30_generic_methods")
  filelist2 = []
  if alt_path == '':
    filelist2 = []
  else:
    filelist2 = get_all_generic_methods_filenames_in_dir(alt_path + "/30_generic_methods")
  result = filelist1 + filelist2

  return result


def get_all_generic_methods_filenames_in_dir(dir):
  return get_all_cf_filenames_under_dir(dir)


def get_all_techniques_filenames(alt_path = ''):
  result = []
  filelist1 = get_all_cf_filenames_under_dir(get_root_dir() + "/tree/50_techniques")
  filelist2 = []
  if alt_path == '':
    filelist2 = []
  else:
    path = os.path.join(alt_path,"50_techniques")
    filelist2 = get_all_cf_filenames_under_dir(path)
  result = filelist1 + filelist2

  return result


def get_all_cf_filenames_under_dir(dir):
  filenames = []
  filenames_add = filenames.append
  for root, dirs, files in os.walk(dir):
    for file in files:
      if not file.startswith("_") and file.endswith(".cf"):
        filenames_add(os.path.join(root, file))
  return filenames


def parse_technique_metadata(technique_content):
  return parse_bundlefile_metadata(technique_content, "technique")


def parse_generic_method_metadata(technique_content):
  return parse_bundlefile_metadata(technique_content, "generic_method")


def parse_bundlefile_metadata(content, bundle_type):
  res = {}
  parameters = []
  multiline = False
  previous_tag = None

  for line in content.splitlines():
    # line should already be unicode
    #unicodeLine = unicode(line,"UTF-8") #line.decode('unicode-escape')

    # Parse metadata tag line
    match = re.match("^\s*#\s*@(\w+)\s(([a-zA-Z_]+)\s+(.*)|.*)$", line, flags=re.UNICODE)
    if match :
      tag = match.group(1)
      # Check ig we are a valid tag
      if tag in tags[bundle_type]:
        # tag "parameter" may be multi-valued
        if tag == "parameter":
          parameters.append({'name': match.group(3), 'description': match.group(4)})
        else:
          res[tag] = match.group(2)
        previous_tag = tag
        continue

    # Parse line without tag, if previous tag was a multiline tag
    if previous_tag is not None and previous_tag in multiline_tags:
      match = re.match("^\s*# (.*)$", line, flags=re.UNICODE)
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
    if re.match("[^#]*bundle\s+agent\s+(\w+)\([^)]*$", match_line, flags=re.UNICODE|re.MULTILINE|re.DOTALL):
      multiline = True

    # read a complete bundle definition
    match = re.match("[^#]*bundle\s+agent\s+(\w+)(\(([^)]+)\))?[^(]*$", match_line, flags=re.UNICODE|re.MULTILINE|re.DOTALL)
    #match = re.match("[^#]*bundle\s+agent\s+(\w+)(\(([^)]+)\))?.*$", line, flags=re.UNICODE)
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

  # If we found any parameters, store them in the res object
  if len(parameters) > 0:
    res['parameter'] = parameters

  expected_tags = tags[bundle_type] + tags["common"]
  if sorted(res.keys()) != sorted(expected_tags):
    missing_keys = [mkey for mkey in expected_tags if mkey not in set(res.keys())]
    name = res['bundle_name'] if 'bundle_name' in res else "unknown"
    raise NcfError("One or more metadata tags not found before the bundle agent declaration (" + ", ".join(missing_keys) + ") in " + name)

  return res


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


def parse_function_call_class_context(function_call):
  """Extract a function call from class context"""
  function_name = function_call['name']
  function_args = [function_arg['value'].replace('\\"', '"') for function_arg in function_call['arguments']]
  # This is valid for string parameters only should improve for inner function
  return function_name + '(' + ','.join(function_args) + ')'


def parse_technique_methods(technique_file):
  res = []

  # Check file exists
  if not os.path.exists(technique_file):
    raise NcfError("No such file: " + technique_file)

  env = os.environ.copy()
  env['RES_OPTIONS'] = 'attempts:0'
  out = check_output(["cf-promises", "-pjson", "-f", technique_file], env=env)
  promises = json.loads(out)

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
      promiser = method['promiser']

      for attribute in method['attributes']:
        if attribute['lval'] == 'usebundle':
          if attribute['rval']['type'] == 'functionCall':
            method_name = attribute['rval']['name']
            args = [arg['value'].replace('\\"', '"') for arg in attribute['rval']['arguments']]
          if attribute['rval']['type'] == 'string':
            method_name = attribute['rval']['value']
        # Extract class context from 'ifvarclass'
        elif attribute['lval'] == 'ifvarclass':
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

      if args:
        res.append({'class_context': promise_class_context, 'method_name': method_name, 'args': args})
      else:
        res.append({'class_context': promise_class_context, 'method_name': method_name})

  return res


def get_hooks(prefix, action, path):
  """Find all hooks file in directory that use the prefix and sort them"""
  # Do not match the following extension, but all other and those that extends (ie exe)
  filtered_extensions = "(?!ex$|example$|disable$|disabled$|rpmsave$|rpmnew$)[^\.]+$"

  # Full regexp is prefix + action + hooks_name + filteredExtension
  regexp = prefix+"\."+action+"\..*\."+filtered_extensions

  files = [f for f in os.listdir(path) if re.match(regexp, f, flags=re.UNICODE)]

  return sorted(files)


def execute_hooks(prefix, action, path, bundle_name):
  """Execute all hooks prefixed by prefix.action from path, all hooks take path and bundle_name as parameter"""
  hooks_path = os.path.join(path, "ncf-hooks.d")
  hooks = get_hooks(prefix, action, hooks_path)
  for hook in hooks:
    hookfile = os.path.join(hooks_path,hook)
    check_output([hookfile,path,bundle_name])


def check_technique_method_call(bundle_name, method_call):
  """Check mandatory keys, if one is missing raise an exception"""
  keys = [ 'method_name' ]
  for key in keys:
    if not key in method_call:
      raise NcfError("Mandatory key "+key+" is missing from a method call in " + bundle_name)

  for key in keys:
    if method_call[key] == "":
      raise NcfError("A method call must have a "+key+", but there is none in " + bundle_name)


def check_technique_metadata(technique_metadata):
  """Check technique metdata, if one is missing raise an exception"""
  mandatory_keys = [ 'name', 'bundle_name', 'method_calls' ]
  for key in mandatory_keys:
    if not key in technique_metadata:
      raise NcfError("Mandatory key "+key+" is missing from Technique metadata")

  non_empty_keys = [ 'name', 'bundle_name']
  for key in non_empty_keys:
    if technique_metadata[key] == "":
      raise NcfError("A technique must have a "+key+", but there is none")

  # If there is no method call, raise an exception
  if len(technique_metadata['method_calls']) == 0:
    raise NcfError("A technique must have at least one method call, and there is none in Technique "+technique_metadata['bundle_name'])

  for call in technique_metadata['method_calls']:
    check_technique_method_call(technique_metadata['bundle_name'], call)


def add_default_values_technique_method_call(method_call):
  """Set default values on some fields in a method call"""
  call = method_call
  if not 'class_context' in call or call['class_context'] == "":
    call['class_context'] = "any"

  return call


def add_default_values_technique_metadata(technique_metadata):
  """Check the technique and set default values on some fields"""
  check_technique_metadata(technique_metadata)

  technique = technique_metadata
  if not 'description' in technique:
    technique['description'] = ""

  if not 'version' in technique:
    technique['version'] = "1.0"

  method_calls=[]
  for call in technique['method_calls']:
    method_calls.append(add_default_values_technique_method_call(call))
  technique['method_calls'] = method_calls
  return technique


def canonify_class_context(class_context):
  """Transform a class context into a canonified one"""
  # We transform the following class context:
  # "Monday.${bundle2.var}.debian.${bundle.var}.linux"
  # into:
  # concat("Monday.",canonify(${bundle2.var}),".debian.",canonify(${bundle.var}),".linux")
  # We have to canonify variables only so the class context is valid and coherent
  regex = re.compile(r'(\${[^\}]*})', flags=re.UNICODE )
  return regex.sub(r'",canonify("\1"),"', class_context)


def generate_technique_content(technique_metadata):
  """Generate technique CFEngine file as string from its metadata"""

  technique = add_default_values_technique_metadata(technique_metadata)

  content = []
  for metadata_key in [ 'name', 'description', 'version' ]:
    # Add commentary for each new line in the metadata
    metadata_value = technique[metadata_key].replace("\n", "\n# ")
    content.append('# @'+ metadata_key +" "+ metadata_value)
  content.append('')
  content.append('bundle agent '+ technique['bundle_name'])
  content.append('{')
  content.append('  methods:')

  # Handle method calls
  for method_call in technique["method_calls"]:
    regex = re.compile(r'(?<!\\)"', flags=re.UNICODE )
    # Treat each argument of the method_call
    if 'args' in method_call:
      args = ['"%s"'%regex.sub(r'\\"', arg) for arg in method_call['args'] ]
      arg_value = ', '.join(args)
    else:
      arg_value = ""
    class_context = canonify_class_context(method_call['class_context'])

    content.append('    "method_call" usebundle => '+method_call['method_name']+'('+arg_value+'),')
    content.append('      ifvarclass => concat("'+class_context+'");')

  content.append('}')

  # Join all lines with \n to get a pretty CFEngine file
  result =  '\n'.join(content)+"\n"

  return result


# FUNCTIONS called directly by the API code
###########################################

def get_all_techniques_metadata(include_methods_calls = True, alt_path = ''):
  all_metadata = {}

  if alt_path != '': sys.stderr.write("INFO: Alternative source path added: %s\n" % alt_path)

  filenames = get_all_techniques_filenames(alt_path)
  errors = []

  for file in filenames:
    with codecs.open(file, encoding="utf-8") as fd:
      content = fd.read()
    try:
      metadata = parse_technique_metadata(content)
      all_metadata[metadata['bundle_name']] = metadata

      if include_methods_calls:
        method_calls = parse_technique_methods(file)
        all_metadata[metadata['bundle_name']]['method_calls'] = method_calls
    except NcfError as e:
      error = NcfError("Could not parse Technique file '" + file + "'", cause=e)
      errors.append(error)
      continue # skip this file, it doesn't have the right tags in - yuk!

  return { "data": all_metadata, "errors": format_errors(errors) }


def get_all_generic_methods_metadata(alt_path = ''):
  all_metadata = {}

  filenames = get_all_generic_methods_filenames(alt_path)
  errors = []

  for file in filenames:
    with codecs.open(file, encoding="utf-8") as fd:
      content = fd.read()
    try:
      metadata = parse_generic_method_metadata(content)
      all_metadata[metadata['bundle_name']] = metadata
    except NcfError as e:
      error = NcfError("Could not parse generic method in '" + file + "'", cause=e )
      errors.append(error)
      continue # skip this file, it doesn't have the right tags in - yuk!

  return { "data": all_metadata, "errors": format_errors(errors) }


def write_technique(technique_metadata, alt_path = ""):
  """Write technique directory from technique_metadata inside the target path"""
  if alt_path == "":
    path = os.path.join(get_root_dir(),"tree")
  else:
    path = alt_path

  try:
    
    # Check if file exists
    bundle_name = technique_metadata['bundle_name']
    filename = os.path.realpath(os.path.join(path, "50_techniques", bundle_name, bundle_name+".cf"))
    # Create parent directory
    if not os.path.exists(os.path.dirname(filename)):
      # parent directory does not exist, we create the technique and its parent directory
      action_prefix = "create"
      os.makedirs(os.path.dirname(filename))
    else:
      # Parent directory exists, we modify the technique
      action_prefix = "modify"

    action = "(write|"+action_prefix+")_technique"

    # Execute pre hooks
    execute_hooks("pre", action, path, bundle_name)

    # Write technique file
    content = generate_technique_content(technique_metadata)
    with codecs.open(filename, "w", encoding="utf-8") as fd:
      fd.write(content)

    # Execute post hooks
    execute_hooks("post", action, path, bundle_name)

  except NcfError as e:
    if not 'bundle_name' in technique_metadata:
      technique_name = ""
    else:
      technique_name = "'"+technique_metadata['bundle_name']+"'"
    message = "Could not write technique "+technique_name+" from path "+path+", cause is: "+ e.message
    raise NcfError(message, e.details)


def delete_technique(technique_name, alt_path=""):
  """Delete a technique directory contained in a path"""
  if alt_path == "":
    path = os.path.join(get_root_dir(),"tree")
  else:
    path = alt_path
  try:
    # Execute pre hooks
    execute_hooks("pre", "delete_technique", path, technique_name)
    # Delete technique file
    filename = os.path.realpath(os.path.join(path, "50_techniques", technique_name))
    shutil.rmtree(filename)
    # Execute post hooks
    execute_hooks("post", "delete_technique", path, technique_name)
  except NcfError as e:
    message = "Could not write technique "+technique_name+" from path "+path+", cause is: "+ e.message
    raise NcfError(message, e.details)


