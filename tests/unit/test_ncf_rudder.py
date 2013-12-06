#!/usr/bin/env python

import unittest
import ncf
import ncf_rudder
import re
import os.path

class TestNcfRudder(unittest.TestCase):

  def setUp(self):
    self.test_expected_reports_csv_file = os.path.realpath('test_expected_reports.csv')
    self.test_expected_reports_csv_content = open(self.test_expected_reports_csv_file).read()

    self.test_metadata_xml_file = os.path.realpath('test_metadata.xml')
    self.test_metadata_xml_content = open(self.test_metadata_xml_file).read()

  def test_expected_reports_from_technique(self):
    expected_reports_string = ncf_rudder.get_technique_expected_reports(technique_metadata)
    self.assertEquals(expected_reports, self.test_expected_reports_csv_content)

  def test_metadata_xml_from_technique(self):
    """The XML content generated from a ncf technique must match the XML content required by Rudder for it's metadata.xml file"""
    metadata_xml_string = ncf_rudder.get_technique_metadata_xml(technique_metadata)
    self.assertEquals(expected_metadata_pure_xml, metadata_xml_string)

  def test_path_for_technique_dir(self):
    root_path = '/tmp/ncf_rudder_tests'
    print os.path.join(root_path, 'bla', '1.0')
    path = ncf_rudder.get_path_for_technique(root_path, technique_metadata)
    assertEquals(os.path.join(root_path, 'bla', '1.0'), path)

  # Other required functions:
  # ncf_rudder.write_technique_for_rudder(path, technique_metadata)
  # ncf_rudder.write_all_techniques_for_rudder(path)

if __name__ == '__main__':
  unittest.main()
