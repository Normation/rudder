#!/usr/bin/env python

import unittest
import ncf
import os.path
import subprocess

class TestNcf(unittest.TestCase):

  def setUp(self):
    self.test_technique_file = os.path.realpath('test_technique.cf')
    self.test_generic_method_file = 'test_generic_method.cf'
    self.technique_content = open(self.test_technique_file).read()
    self.generic_method_content = open(self.test_generic_method_file).read()

  def test_get_ncf_root_dir(self):
    self.assertEquals(ncf.get_root_dir(), os.path.realpath(os.path.dirname(os.path.realpath(__file__)) + "/../../"))

  #####################################
  # Generic tests for parsing .cf files
  #####################################

  def test_parse_bundlefile_empty(self):
    """Attempting to parse an empty string should raise an exception"""
    self.assertRaises(Exception, ncf.parse_bundlefile_metadata, "")

  def test_parse_bundlefile_incomplete(self):
    """Attempting to parse a bundle file with metadata after the bundle agent declaration should raise an exception"""
    self.assertRaises(Exception, ncf.parse_bundlefile_metadata, """# @name A name
                                      bundle agent thingy {
                                      }
                                      # @description bla bla
                                      # @version 1.0""")

  #########################
  # Technique parsing tests
  #########################

  def test_parse_technique(self):
    """Parsing should return a dict with all defined technique tags"""
    metadata = ncf.parse_technique_metadata(self.technique_content)
    self.assertEqual(sorted(metadata.keys()), sorted(ncf.tags["technique"]+ncf.tags["common"]))

  def test_parse_technique_data(self):
    """Parsing should return a dict with the data from the test technique"""
    metadata = ncf.parse_technique_metadata(self.technique_content)
    self.assertEqual(metadata['name'], "Bla Technique for evaluation of parsingness")
    self.assertEqual(metadata['description'], "This meta-Technique is a sample only, allowing for testing.")
    self.assertEqual(metadata['version'], "0.1")
    self.assertEqual(metadata['bundle_name'], "bla")
    self.assertEqual(metadata['bundle_args'], [])

  ##############################
  # Generic method parsing tests
  ##############################

  def test_parse_generic_method(self):
    """Parsing a generic method should return a dict with all defined generic_method tags"""
    metadata = ncf.parse_generic_method_metadata(self.generic_method_content)
    self.assertEqual(sorted(metadata.keys()), sorted(ncf.tags["generic_method"]+ncf.tags["common"]))

  def test_parse_generic_method_data(self):
    """Parsing should return a dict with the data from the test generic_method"""
    metadata = ncf.parse_generic_method_metadata(self.generic_method_content)
    self.assertEqual(metadata['bundle_name'], "package_install_version")
    self.assertEqual(metadata['bundle_args'], ["package_name", "package_version"])
    self.assertEqual(metadata['name'], "Package install")
    self.assertEqual(metadata['class_prefix'], "package_install")
    self.assertEqual(metadata['class_parameter'], "package_name")
    self.assertEqual(metadata['class_parameter_id'], 1)
    self.assertEqual(len(metadata), len(ncf.tags["generic_method"]+ncf.tags["common"]))

  ###########################################################
  # Tests to obtain the generic methods that a Technique uses
  ###########################################################

  def test_parse_technique_generic_method_calls_nonexistant_file(self):
    """Attempting to parse a non existant file should return an exception"""
    self.assertRaises(Exception, ncf.parse_technique_methods, "/dev/nonexistant")

  def test_parse_technique_generic_method_calls(self):
    """Parsing a technique should return a list of it's generic method calls"""
    bundle_calls = ncf.parse_technique_methods(self.test_technique_file)
    expected = [  { 'method_name': 'package_install_version', 'args': ['${bla.apache_package_name}', '2.2.11'], 'class_context': 'any' },
                  { 'method_name': 'service_start', 'args': ['${bla.apache_package_name}'], 'class_context': 'cfengine' },
                  { 'method_name': 'package_install', 'args': ['openssh-server'], 'class_context': 'cfengine' },
               ]
    self.assertEqual(bundle_calls, expected)

  def test_parse_technique_generic_method_calls_strings(self):
    """Parsing a technique should return a list of it's generic method calls even if they are string literals"""
    bundle_calls = ncf.parse_technique_methods(self.test_technique_file)
    expected = [  { 'method_name': 'package_install_version', 'args': ['${bla.apache_package_name}', '2.2.11'], 'class_context': 'any' },
                  { 'method_name': 'service_start', 'args': ['${bla.apache_package_name}'], 'class_context': 'cfengine' },
                  { 'method_name': 'package_install', 'args': ['openssh-server'], 'class_context': 'cfengine' },
               ]
    self.assertEqual(bundle_calls, expected)

  #####################################
  # Tests for reading all metadata info
  #####################################

  def test_get_all_generic_methods_metadata(self):
    """get_all_generic_methods_metadata should return a list of all generic_methods with all defined metadata tags"""
    metadata = ncf.get_all_generic_methods_metadata()

    number_generic_methods = len(ncf.get_all_generic_methods_filenames())
    self.assertEquals(number_generic_methods, len(metadata))

  def test_get_all_techniques_metadata(self):
    """get_all_techniques_metadata should return a list of all techniques with all defined metadata tags and methods_called"""
    metadata = ncf.get_all_techniques_metadata()

    number = len(ncf.get_all_techniques_filenames())
    self.assertEquals(number, len(metadata))

if __name__ == '__main__':
  unittest.main()
