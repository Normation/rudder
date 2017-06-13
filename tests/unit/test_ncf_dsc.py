#!/usr/bin/env python
# -*- coding: utf-8 -*-

import unittest
import ncf
import ncf_constraints
import ncf_dsc
import os.path
import subprocess
import shutil
from pprint import pprint

class TestNcf(unittest.TestCase):

  def setUp(self):
    self.technique_file = os.path.realpath('technique_metadata_test_content.cf')
    with open(self.technique_file) as fd:
      self.technique_content = fd.read()

    self.all_methods = ncf.get_all_generic_methods_metadata()["data"]["generic_methods"]

    # For test, for now add dsc_support to all method
    for method in self.all_methods:
      self.all_methods[method]["dsc_support"] = True

    self.technique_metadata = ncf.parse_technique_metadata(self.technique_content)['result']
    method_calls = ncf.parse_technique_methods(self.technique_file)
    self.technique_metadata['method_calls'] = method_calls


    self.dsc_technique_file = os.path.realpath('test_technique.ps1')
    with open(self.dsc_technique_file) as fd:
      self.dsc_content = fd.read()


  #####################################
  # Tests for writing/delete Techniques all metadata info
  #####################################
    
  def test_generate_technique_content(self):
    """Test if dsc policy file is correctly generated from a ncf technique"""

    # Join all lines with \n to get a pretty technique file
    generated_result = ncf_dsc.get_ps1_content(self.technique_metadata, self.all_methods)

    generated_split = generated_result.split("\n")
    expected_split  = self.dsc_content.split("\n")
    self.assertEqual(generated_split, expected_split)


if __name__ == '__main__':
  unittest.main()
