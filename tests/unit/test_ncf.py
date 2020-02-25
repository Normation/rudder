#!/usr/bin/python
# -*- coding: utf-8 -*-

import unittest
import ncf
import os.path
import subprocess
import shutil
from pprint import pprint

class TestNcf(unittest.TestCase):

  def setUp(self):
    self.test_technique_file = os.path.realpath('test_technique.cf')
    self.test_generic_method_file = 'test_generic_method.cf'
    with open(self.test_technique_file) as fd:
      self.technique_content = fd.read()
    with open(self.test_generic_method_file) as fd:
      self.generic_method_content = fd.read()
    self.all_methods = ncf.get_all_generic_methods_metadata()["data"]["generic_methods"]

    self.technique_metadata = ncf.parse_technique_metadata(self.technique_content)['result']
    method_calls = ncf.parse_technique_methods(self.test_technique_file, self.all_methods)
    self.technique_metadata['method_calls'] = method_calls

    self.technique_metadata_test = { 'name': 'ncf technique method argument escape test', 'description': "This is a bundle to test ncf's Python lib", 'version': '0.1', 'bundle_name': 'content_escaping_test', 'bundle_args': [], 'parameter': [],
        'method_calls': [
            { 'method_name': 'package_install_version', "component" : "Install a package with correct version", 'args': ['apache2', '2.2.11'], 'class_context': 'any' },
            { 'method_name': 'file_replace_lines', "component" : "Edit conf file",'args': ['/etc/httpd/conf/httpd.conf', 'ErrorLog \"/var/log/httpd/error_log\"', 'ErrorLog "/projet/logs/httpd/error_log"'],  'class_context': 'redhat' },
          ]
        }
    self.technique_metadata_test_content = os.path.realpath('technique_metadata_test_content.cf')
    all_tags = ncf.tags["generic_method"]
    self.methods_expected_tags = [ tag for tag in all_tags if not tag in ncf.optionnal_tags["generic_method"] ]

    with open(self.technique_metadata_test_content) as fd:
      self.technique_test_expected_content = fd.read().split("\n")


  def test_get_ncf_root_dir(self):
    self.assertEqual(ncf.get_root_dir(), os.path.realpath(os.path.dirname(os.path.realpath(__file__)) + "/../../"))

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
    metadata = ncf.parse_technique_metadata(self.technique_content)['result']
    self.assertEqual(sorted(metadata.keys()), sorted(ncf.tags["technique"]))

  def test_parse_technique_data(self):
    """Parsing should return a dict with the data from the test technique"""
    metadata = ncf.parse_technique_metadata(self.technique_content)['result']
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
    metadata = ncf.parse_generic_method_metadata(self.generic_method_content)['result']
    self.assertTrue(set(metadata.keys()).issuperset(set(self.methods_expected_tags)))

  def test_parse_generic_method_data(self):
    """Parsing should return a dict with the data from the test generic_method"""
    metadata = ncf.parse_generic_method_metadata(self.generic_method_content)['result']
    self.assertEqual(metadata['bundle_name'], "package_install_version")
    self.assertEqual(metadata['bundle_args'], ["package_name", "package_version"])
    self.assertEqual(metadata['name'], "Package install")
    self.assertEqual(metadata['description'], "Install a package by name from the default system package manager")
    self.assertEqual(metadata['parameter'], [ { 'constraints': { "allow_empty_string": False, "allow_whitespace_string": False, "max_length" : 16384 }, 'type' : 'string', 'name': 'package_name', 'description': 'Name of the package to install'},{ 'constraints': { "allow_empty_string": False, "allow_whitespace_string": False, "max_length" : 16384 }, 'type': 'version', 'name': 'package_version', 'description': 'Version of the package to install'}])
    self.assertEqual(metadata['class_prefix'], "package_install")
    self.assertEqual(metadata['class_parameter'], "package_name")
    self.assertEqual(metadata['class_parameter_id'], 1)
    self.assertEqual(metadata['agent_version'], ">= 3.6")
    self.assertEqual(len(metadata), len(self.methods_expected_tags))
    self.assertEqual(metadata['agent_version'], ">= 3.6")

  ###########################################################
  # Tests to obtain the generic methods that a Technique uses
  ###########################################################

  def test_parse_technique_generic_method_calls_nonexistant_file(self):
    """Attempting to parse a non existant file should return an exception"""
    self.assertRaises(Exception, ncf.parse_technique_methods, "/dev/nonexistant")

  def test_parse_technique_generic_method_calls(self):
    """Parsing a technique should return a list of it's generic method calls"""
    bundle_calls = ncf.parse_technique_methods(self.test_technique_file, self.all_methods)
    expected = [
        { 'method_name': u'package_install_version', 'component': u'ph1', 'args': [u'${bla.apache_package_name}', u'2.2.11'], 'class_context': u'any' }
      , { 'method_name': u'service_start', 'component': u'ph2', 'args': [u'${bla.apache_package_name}'], 'class_context': u'cfengine' }
      , { 'method_name': u'package_install', 'component': u'ph3', 'args': [u'openssh-server'], 'class_context': u'cfengine' }
      , { 'method_name': u'command_execution', 'component': u'ph4', 'args': ['/bin/echo "test"'], 'class_context': 'cfengine'}
    ]
    self.assertEqual(bundle_calls, expected)

  def test_parse_technique_generic_method_calls_strings(self):
    """Parsing a technique should return a list of it's generic method calls even if they are string literals"""
    bundle_calls = ncf.parse_technique_methods(self.test_technique_file, self.all_methods)
    expected = [
        { 'method_name': u'package_install_version', 'component': u'ph1', 'args': [u'${bla.apache_package_name}', u'2.2.11'], 'class_context': u'any' }
      , { 'method_name': u'service_start', 'component': u'ph2', 'args': [u'${bla.apache_package_name}'], 'class_context': u'cfengine' }
      , { 'method_name': u'package_install', 'component': u'ph3', 'args': [u'openssh-server'], 'class_context': u'cfengine' }
      , { 'method_name': u'command_execution', 'component': u'ph4', 'args': ['/bin/echo "test"'], 'class_context': 'cfengine'}
    ]
    self.assertEqual(bundle_calls, expected)

  #####################################
  # Tests for reading all metadata info
  #####################################

  def test_get_all_generic_methods_filenames(self):
    """test_get_all_generic_methods_filenames should return a list of all generic_methods files"""
    base_dir = ncf.get_root_dir() + "/tree/30_generic_methods"
    alternative_path = os.path.dirname(os.path.realpath(__file__)) + "/test_methods"

    # Get list of generic_methods without prefix "_" on the filesystem
    list_methods_files = []
    ## Get recursivly each promises in the basic path and the alternative one
    list_methods_files += [os.path.join(full_path,filename) for full_path, dirname, files in os.walk(base_dir) for filename in files if not filename.startswith('_') and filename.endswith('.cf')]

    filenames = ncf.get_all_generic_methods_filenames()

    filenames.sort()
    list_methods_files.sort()

    self.assertEqual(filenames, list_methods_files)

  def test_get_all_generic_methods_metadata(self):
    """get_all_generic_methods_metadata should return a list of all generic_methods with all defined metadata tags"""
    metadata = ncf.get_all_generic_methods_metadata()["data"]["generic_methods"]

    number_generic_methods = len(ncf.get_all_generic_methods_filenames())
    self.assertEqual(number_generic_methods, len(metadata))

  def test_get_all_generic_methods_metadata_with_arg(self):
    """get_all_generic_methods_metadata should return a list of all generic_methods with all defined metadata tags"""
    metadata = ncf.get_all_generic_methods_metadata()["data"]["generic_methods"]

    number_generic_methods = len(ncf.get_all_generic_methods_filenames())
    self.assertEqual(number_generic_methods, len(metadata))

  def test_get_all_techniques_metadata(self):
    """get_all_techniques_metadata should return a list of all techniques with all defined metadata tags and methods_called"""
    metadata = ncf.get_all_techniques_metadata()
    data = metadata["data"]["techniques"]
    errors = metadata["errors"]
    all_metadata = len(data) + len(errors)

    all_files = len(ncf.get_all_techniques_filenames())

    self.assertEqual(all_files, all_metadata)

  def test_parse_technique_methods_unescape_double_quotes(self):
    test_parse_technique_methods_unescape_double_quotes_calls = ncf.parse_technique_methods(self.technique_metadata_test_content, self.all_methods)
    expected_result = [
        { 'method_name': u'package_install_version', 'class_context': u'any', 'component': u'Install a package with correct version', 'args': [u'apache2', u'2.2.11'] },
        { 'method_name': u'file_replace_lines', 'class_context': u'redhat', 'component': u'Edit conf file', 'args': [u'/etc/httpd/conf/httpd.conf', u'ErrorLog "/var/log/httpd/error_log"', u'ErrorLog "/projet/logs/httpd/error_log"'] }
                      ]
    self.assertEqual(expected_result, test_parse_technique_methods_unescape_double_quotes_calls)


  #########################
  # Utility methods tests
  #########################

  def test_class_context_and(self):
    """Ensure the class_context_and method behaves as expected for various cases"""
    self.assertEqual("b", ncf.class_context_and("any", "b"))
    self.assertEqual("a", ncf.class_context_and("a", "any"))
    self.assertEqual("a.b", ncf.class_context_and("a", "b"))
    self.assertEqual("a.B", ncf.class_context_and("a", "B"))
    self.assertEqual("(a.b).c", ncf.class_context_and("a.b", "c"))
    self.assertEqual("(a|b).(c&d)", ncf.class_context_and("a|b", "c&d"))
    self.assertEqual("c&d", ncf.class_context_and("any", "c&d"))
    self.assertEqual("c.d", ncf.class_context_and("any", "c.d"))
    self.assertEqual("a|b", ncf.class_context_and("a|b", "any"))
    self.assertEqual("any", ncf.class_context_and("any", "any"))

if __name__ == '__main__':
  unittest.main()
