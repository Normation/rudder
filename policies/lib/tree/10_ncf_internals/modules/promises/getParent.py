#!/bin/sh
# vim: syntax=python
''':'
# First try to run this script with python3, else run with python
if command -v python3 >/dev/null 2>/dev/null; then
  exec python3 "$0" "$@"
elif command -v python >/dev/null 2>/dev/null; then
  exec python  "$0" "$@"
else
  exec python2 "$0" "$@"
fi
'''

import os
import sys
import glob

def get_parent(path):
  return os.path.dirname(os.path.realpath(os.path.normpath(path)))

def get_parents(path, parent_dirs):
  if (os.path.isfile(path)):
    current_dir = get_parent(path)
  else:
    current_dir = os.path.realpath(os.path.normpath(path))

  while current_dir not in parent_dirs and current_dir != '/':
    parent_dirs.add(current_dir)
    current_dir = get_parent(current_dir)
  return parent_dirs

def print_result(directories):
  output = "@parentDirectories={ "
  for i in directories:
    output += "\"{0}\",".format(i)
  print(output[:-1] + "}")

def exec_module(user_input):
  parent_dirs = set()
  targets = glob.glob(user_input)
  for i in targets:
    parents_dirs = get_parents(i, parent_dirs)
  return sorted(parent_dirs)

if __name__ == '__main__':
    print_result(exec_module(sys.argv[1]))
