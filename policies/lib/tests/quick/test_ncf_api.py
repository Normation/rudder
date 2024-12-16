#!/usr/bin/python
# -*- coding: utf-8 -*-

import sys
import os
DIRNAME = os.path.dirname(os.path.abspath(__file__))
GIT_ROOT = DIRNAME + '/../..'
TESTLIB_PATH = DIRNAME + '/../testlib'
sys.path.insert(0, GIT_ROOT + '/tests')
import ncf
import os.path
import subprocess
import shutil
import avocado

class TestNcf(avocado.Test):

  def setUp(self):
    self.dirname = os.path.dirname(os.path.realpath(__file__))
    self.test_generic_method_file = self.dirname + '/test_ncf_api_assets/test_generic_method.cf'
    with open(self.test_generic_method_file) as fd:
      self.generic_method_content = fd.read()
    self.all_methods = ncf.get_all_generic_methods_metadata()["data"]["generic_methods"]

    all_tags = ncf.tags["generic_method"]
    self.methods_expected_tags = [ tag for tag in all_tags if not tag in ncf.optionnal_tags["generic_method"] ]


  def test_get_ncf_root_dir(self):
    self.assertEqual(ncf.get_root_dir(), os.path.realpath(os.path.dirname(os.path.realpath(__file__)) + "/../../"))

  #####################################
  # Generic tests for parsing .cf files
  #####################################

  def test_parse_bundlefile_empty(self):
    """Attempting to parse an empty string should raise an exception"""
    self.assertRaises(Exception, ncf.parse_generic_method_metadata, "")

  def test_parse_bundlefile_incomplete(self):
    """Attempting to parse a bundle file with metadata after the bundle agent declaration should raise an exception"""
    self.assertRaises(Exception, ncf.parse_generic_method_metadata, """# @name A name
                                      bundle agent thingy {
                                      }
                                      # @description bla bla
                                      # @version 1.0""")


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
    self.assertEqual(len(metadata), len(self.methods_expected_tags))

  #####################################
  # Tests for reading all metadata info
  #####################################

  def test_get_all_generic_methods_filenames(self):
    """test_get_all_generic_methods_filenames should return a list of all generic_methods files"""
    base_dir = ncf.get_root_dir() + "/tree/30_generic_methods"
    conf_repo_dir = "/var/rudder/configuration-repository/ncf/30_generic_methods"
    alternative_path = os.path.dirname(os.path.realpath(__file__)) + "/test_methods"

    # Get list of generic_methods without prefix "_" on the filesystem
    list_methods_files = []
    ## Get recursively each promises in the basic path and the alternative one
    list_methods_files += [os.path.join(full_path,filename) for full_path, dirname, files in os.walk(base_dir) for filename in files if not filename.startswith('_') and filename.endswith('.cf')]
    list_methods_files += [os.path.join(full_path,filename) for full_path, dirname, files in os.walk(conf_repo_dir) for filename in files if not filename.startswith('_') and filename.endswith('.cf')]

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
