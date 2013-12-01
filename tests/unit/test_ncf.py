#!/usr/bin/env python

import unittest
import ncf

class TestNcf(unittest.TestCase):

  def setUp(self):
    self.technique_content = open('test_technique.cf').read()
    self.generic_method_content = open('test_generic_method.cf').read()

  def test_parse_technique(self):
    """Parsing should return a dict with all defined technique tags"""
    metadata = ncf.parse_technique_metadata(self.technique_content)
    self.assertEqual(sorted(metadata.keys()), sorted(ncf.tags["technique"]))

  def test_parse_technique_data(self):
    """Parsing should return a dict with the data from the test technique"""
    metadata = ncf.parse_technique_metadata(self.technique_content)
    self.assertEqual(metadata['name'], "Bla Technique for evaluation of parsingness")
    self.assertEqual(metadata['description'], "This meta-Technique is a sample only, allowing for testing.")
    self.assertEqual(metadata['version'], "0.1")

  def test_parse_bundlefile_empty(self):
    """Attempting to parse an empty string should raise an exception"""
    with self.assertRaises(Exception):
      ncf.parse_bundlefile_metadata("")

  def test_parse_bundlefile_incomplete(self):
    """Attempting to parse a bundle file with metadata after the bundle agent declaration should raise an exception"""
    with self.assertRaises(Exception):
      ncf.parse_bundlefile_metadata("""# @name A name
                                      bundle agent thingy {
                                      }
                                      # @description bla bla
                                      # @version 1.0""")

  def test_parse_generic_method(self):
    """Parsing a generic method should return a dict with all defined generic_method tags"""
    metadata = ncf.parse_generic_method_metadata(self.generic_method_content)
    self.assertEqual(sorted(metadata.keys()), sorted(ncf.tags["generic_method"]))

if __name__ == '__main__':
  unittest.main()
