# This is a Python module containing functions to parse and analyze ncf components

# This module is designed to run on the latest major versions of the most popular
# server OSes (Debian, Red Hat/CentOS, Ubuntu, SLES, ...)
# At the time of writing (November 2013) these are Debian 7, Red Hat/CentOS 6,
# Ubuntu 12.04 LTS, SLES 11, ...
# The version of Python in all of these is >= 2.6, which is therefore what this
# module must support

import re

tags = {}
tags["generic_method"] = ["name", "class_prefix"]
tags["technique"] = ["name", "description", "version"]

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

    if sorted(res.keys()) == sorted(tags[bundle_type]):
      # Found all the tags we need, stop parsing
      break

    if re.match("[^#]*bundle\s+agent\s+", line):
      # Any tags should come before the "bundle agent" declaration
      break
      
  if sorted(res.keys()) != sorted(tags[bundle_type]):
    missing_keys = [mkey for mkey in tags[bundle_type] if mkey not in set(res.keys())]
    raise Exception("One or more metadata tags not found before the bundle agent declaration (" + ", ".join(missing_keys) + ")")

  return res
