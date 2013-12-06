#!/usr/bin/env python

import unittest
import ncf
import ncf_rudder
import re
import os.path
import xml.etree.cElementTree as XML

class TestNcfRudder(unittest.TestCase):

  def setUp(self):
    self.test_expected_reports_csv_file = os.path.realpath('test_expected_reports.csv')
    self.test_expected_reports_csv_content = open(self.test_expected_reports_csv_file).read()
    
    self.test_technique_file = os.path.realpath('test_technique.cf')
    self.technique_content = open(self.test_technique_file).read()
    self.technique_metadata = ncf.parse_technique_metadata(self.technique_content)
    method_calls = ncf.parse_technique_methods(self.test_technique_file)
    self.technique_metadata['method_calls'] = method_calls

    self.test_metadata_xml_file = os.path.realpath('test_metadata.xml')
    self.test_metadata_xml_content = open(self.test_metadata_xml_file).read()

  def test_expected_reports_from_technique(self):
    expected_reports_string = ncf_rudder.get_technique_expected_reports(self.technique_metadata)
    self.assertEquals(expected_reports_string, self.test_expected_reports_csv_content)

  def test_metadata_xml_from_technique(self):
    """The XML content generated from a ncf technique must match the XML content required by Rudder for it's metadata.xml file"""
    metadata_xml_string = ncf_rudder.get_technique_metadata_xml(self.technique_metadata)
    s = XML.tostring(metadata_xml_string)
    self.assertEquals(expected_metadata_pure_xml, metadata_xml_string)

  def test_path_for_technique_dir(self):
    root_path = '/tmp/ncf_rudder_tests'
    path = ncf_rudder.get_path_for_technique(root_path, self.technique_metadata)
    self.assertEquals(os.path.join(root_path, 'bla', '0.1'), path)

  # Other required functions:
  # ncf_rudder.write_technique_for_rudder(path, technique_metadata)
  # ncf_rudder.write_all_techniques_for_rudder(path)

if __name__ == '__main__':
  unittest.main()
