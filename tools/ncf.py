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

tags = {}
tags["common"] = ["bundle_name", "bundle_args"]
tags["generic_method"] = ["name", "class_prefix", "class_parameter", "class_parameter_id"]
tags["technique"] = ["name", "description", "version"]

def get_root_dir():
  return os.path.realpath(os.path.dirname(__file__) + "/../")

def get_all_generic_methods_filenames():
  return get_all_generic_methods_filenames_in_dir(get_root_dir() + "/tree/30_generic_methods")

def get_all_generic_methods_filenames_in_dir(dir):
  return get_all_cf_filenames_under_dir(dir)

def get_all_techniques_filenames():
  return get_all_cf_filenames_under_dir(get_root_dir() + "/tree/50_techniques")

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

  for line in content.splitlines():
    for tag in tags[bundle_type]:
      match = re.match("^\s*#\s*@" + tag + "\s+(.*)$", line)
      if match :
        res[tag] = match.group(1)

    match = re.match("[^#]*bundle\s+agent\s+([^(]+)\(?([^)]*)\)?.*$", line)
    if match:
      res['bundle_name'] = match.group(1)
      res['bundle_args'] = []
      res['bundle_args'] += [x.strip() for x in match.group(2).split(',')]

      # Any tags should come before the "bundle agent" declaration
      break
      
  # The tag "class_parameter_id" is a magic tag, it's value is built from class_parameter and the list of args
  if "class_parameter_id" in tags[bundle_type]:
    try:
      res['class_parameter_id'] = res['bundle_args'].index(res['class_parameter'])+1
    except:
      res['class_parameter_id'] = 0

  expected_tags = tags[bundle_type] + tags["common"]
  if sorted(res.keys()) != sorted(expected_tags):
    missing_keys = [mkey for mkey in expected_tags if mkey not in set(res.keys())]
    raise Exception("One or more metadata tags not found before the bundle agent declaration (" + ", ".join(missing_keys) + ")")

  return res

def parse_technique_methods(technique_file):
  res = []

  # Check file exists
  if not os.path.exists(technique_file):
    raise Exception("No such file: " + technique_file)

  out = subprocess.check_output(["cf-promises", "-pjson", "-f", technique_file])
  promises = json.loads(out)

  # Sanity check: if more than one bundle, this is a weird file and I'm quitting
  if len(promises['bundles']) != 1:
    raise Exception("There is not exactly one bundle in this file, aborting")

  # Sanity check: the bundle must be of type agent
  if promises['bundles'][0]['bundleType'] != 'agent':
    raise Exception("This bundle if not a bundle agent, aborting")

  methods = [promiseType for promiseType in promises['bundles'][0]['promiseTypes'] if promiseType['name']=="methods"][0]['contexts']
  #print "context = " + methods[0]['contexts']['name']

  for context in methods:
    class_context = context['name']

    for method in context['promises']:
      method_name = None
      args = None

      promiser = method['promiser']

      for attribute in method['attributes']:
        if attribute['lval'] == 'usebundle':
          if attribute['rval']['type'] == 'functionCall':
            method_name = attribute['rval']['name']
            args = [arg['value'] for arg in attribute['rval']['arguments']]
          if attribute['rval']['type'] == 'string':
            method_name = attribute['rval']['value']

      if args:
        res.append({'class_context': class_context, 'method_name': method_name, 'args': args})
      else:
        res.append({'class_context': class_context, 'method_name': method_name})

  return res
