#!/usr/bin/env python
# -*- coding: utf-8 -*-

import unittest
import ncf
import ncf_rudder
import re
import os.path
import sys
import xml.etree.cElementTree as XML
from pprint import pprint

class TestNcfRudder(unittest.TestCase):

  def setUp(self):
    self.maxDiff = None
    self.test_expected_reports_csv_file = os.path.realpath('test_expected_reports.csv')
    with open(self.test_expected_reports_csv_file) as fd:
      self.test_expected_reports_csv_content = fd.read()
    
    self.test_technique_file = os.path.realpath('test_technique.cf')
    with open(self.test_technique_file) as fd:
      self.technique_content = fd.read()
    self.technique_metadata = ncf.parse_technique_metadata(self.technique_content)
    method_calls = ncf.parse_technique_methods(self.test_technique_file)
    self.technique_metadata['method_calls'] = method_calls

    self.test_reporting = os.path.realpath('test_technique_reporting.cf')
    with open(self.test_reporting) as fd:
      self.reporting_content = fd.read()
    self.reporting_metadata = ncf.parse_technique_metadata(self.reporting_content)
    reporting_method_calls = ncf.parse_technique_methods(self.test_reporting)
    self.reporting_metadata['method_calls'] = reporting_method_calls

    self.test_reporting_with_var = os.path.realpath('test_technique_with_variable.cf')
    with open(self.test_reporting_with_var) as fd:
      self.reporting_with_var_content = fd.read()
    self.reporting_with_var_metadata = ncf.parse_technique_metadata(self.reporting_with_var_content)
    reporting_with_var_method_calls = ncf.parse_technique_methods(self.test_reporting_with_var)
    self.reporting_with_var_metadata['method_calls'] = reporting_with_var_method_calls


    self.test_any_technique = os.path.realpath('test_technique_any.cf')
    with open(self.test_any_technique) as fd:
      self.any_technique_content = fd.read()
    self.any_technique_metadata = ncf.parse_technique_metadata(self.any_technique_content)
    any_technique_method_calls = ncf.parse_technique_methods(self.test_any_technique)
    self.any_technique_metadata['method_calls'] = any_technique_method_calls

    self.test_metadata_xml_file = os.path.realpath('test_metadata.xml')
    with open(self.test_metadata_xml_file) as fd:
      self.test_metadata_xml_content = fd.read()


    # Testing Techniques with quote
    self.test_technique_with_quote_file = os.path.realpath('test_technique_with_quote.cf')
    self.technique_with_quote_content = open(self.test_technique_with_quote_file).read()
    self.technique_with_quote_metadata = ncf.parse_technique_metadata(self.technique_with_quote_content)
    method_with_quote_calls = ncf.parse_technique_methods(self.test_technique_with_quote_file)
    self.technique_with_quote_metadata['method_calls'] = method_with_quote_calls

    self.test_expected_reports_with_quote_csv_file = os.path.realpath('test_expected_reports_with_quote.csv')
    self.test_expected_reports_with_quote_csv_content = open(self.test_expected_reports_with_quote_csv_file).read()

    self.test_metadata_with_quote_xml_file = os.path.realpath('test_metadata_with_quote.xml')
    self.test_metadata_with_quote_xml_content = open(self.test_metadata_with_quote_xml_file).read()

  def test_expected_reports_from_technique(self):
    expected_reports_string = ncf_rudder.get_technique_expected_reports(self.technique_metadata)
    self.assertEqual(expected_reports_string, self.test_expected_reports_csv_content)

  def test_metadata_xml_from_technique(self):
    """The XML content generated from a ncf technique must match the XML content required by Rudder for it's metadata.xml file"""
    metadata_xml_string = ncf_rudder.get_technique_metadata_xml(self.technique_metadata)
    expected_metadata_pure_xml = self.test_metadata_xml_content
    self.assertEqual(expected_metadata_pure_xml, metadata_xml_string)

  def test_path_for_technique_dir(self):
    root_path = '/tmp/ncf_rudder_tests'
    path = ncf_rudder.get_path_for_technique(root_path, self.technique_metadata)
    self.assertEqual(os.path.join(root_path, 'bla', '0.1'), path)

  def test_rudder_reporting_file(self):
    root_path = '/tmp/ncf_rudder_tests_reporting'
    ncf_rudder.write_technique_for_rudder(root_path, self.reporting_metadata)
    result = os.path.exists(os.path.realpath(os.path.join(root_path, 'bla', '0.1', "rudder_reporting.st")))
    self.assertTrue(result)

  def test_any_technique_reporting_file(self):
    root_path = '/tmp/ncf_rudder_tests_any'
    ncf_rudder.write_technique_for_rudder(root_path, self.any_technique_metadata)
    result = not os.path.exists(os.path.realpath(os.path.join(root_path, 'bla', '0.1', "rudder_reporting.st")))
    self.assertTrue(result)


  # Testing Techniques with quotes
  def test_expected_reports_with_quote(self):
    expected_reports_string = ncf_rudder.get_technique_expected_reports(self.technique_with_quote_metadata)
    self.assertEquals(expected_reports_string, self.test_expected_reports_with_quote_csv_content)

  def test_metadate_with_quote(self):
    metadata_xml_string = ncf_rudder.get_technique_metadata_xml(self.technique_with_quote_metadata)
    expected_metadata_pure_xml = self.test_metadata_with_quote_xml_content
    self.assertEquals(expected_metadata_pure_xml, metadata_xml_string)

  def test_rudder_reporting_content(self):

    expected_result = []
    expected_result.append('bundle agent bla_rudder_reporting')
    expected_result.append('{')
    expected_result.append('  methods:')
    expected_result.append('')
    expected_result.append('    !(debian)::')
    expected_result.append('      "dummy_report" usebundle => _classes_noop("service_start_apache2");')
    expected_result.append('      "dummy_report" usebundle => logger_rudder("Not applicable", "service_start_apache2");')
    expected_result.append('')
    expected_result.append('    !(service_start_apache2_repaired)::')
    expected_result.append('      "dummy_report" usebundle => _classes_noop("package_install_openssh_server");')
    expected_result.append('      "dummy_report" usebundle => logger_rudder("Not applicable", "package_install_openssh_server");')
    expected_result.append('')
    expected_result.append('    !(!service_start_apache2_repaired.debian)::')
    expected_result.append('      "dummy_report" usebundle => _classes_noop("command_execution__bin_date");')
    expected_result.append('      "dummy_report" usebundle => logger_rudder("Not applicable", "command_execution__bin_date");')
    expected_result.append('}')

    # Join all lines with \n
    result = '\n'.join(expected_result)+"\n"

    generated_result = ncf_rudder.generate_rudder_reporting(self.reporting_metadata)

    self.assertEqual(result, generated_result)


  def test_rudder_reporting_with_variable_content(self):


    expected_result = []
    expected_result.append('bundle agent Test_technique_with_variable_rudder_reporting')
    expected_result.append('{')
    expected_result.append('  methods:')
    expected_result.append('')
    expected_result.append('      "dummy_report" usebundle => _classes_noop("file_create_${sys.workdir}_module_env"),')
    expected_result.append('                    ifvarclass => concat("!(directory_create_",canonify("${sys.workdir}"),"_module_repaired)");')
    expected_result.append('      "dummy_report" usebundle => logger_rudder("Not applicable", "file_create_${sys.workdir}_module_env"),')
    expected_result.append('                    ifvarclass => concat("!(directory_create_",canonify("${sys.workdir}"),"_module_repaired)");')
    expected_result.append('}')

    # Join all lines with \n
    result = '\n'.join(expected_result)+"\n"

    generated_result = ncf_rudder.generate_rudder_reporting(self.reporting_with_var_metadata)

    self.assertEqual(result, generated_result)


  def test_category_xml_content(self):

    content = []
    content.append('<xml>')
    content.append('  <name>User Techniques</name>')
    content.append('  <description>')
    content.append('    Techniques created using the Technique editor.')
    content.append('  </description>')
    content.append('</xml>')

    # Join all lines with \n to get a pretty xml
    result = '\n'.join(content)+"\n"
    generated_result = ncf_rudder.get_category_xml()

    self.assertEqual(result, generated_result)

  # Other required functions:
  # ncf_rudder.write_technique_for_rudder(path, technique_metadata)
  # ncf_rudder.write_all_techniques_for_rudder(path)


  def test_canonify(self):
    result = ncf_rudder.canonify("ascii @&_ string")
    self.assertEquals(result, "ascii_____string")

    # python/ncf reads UTF-8 files and produces u'' strings in python2 and '' strings in python3
    # python2 tests
    if sys.version_info[0] == 2:
      # unicode in source file -> interpreted as unicode with u'' -> correct iso in python string (ncf builder use case)
      result = ncf_rudder.canonify(u'héhé')
      self.assertEquals(result, 'h__h__')

    # python3 tests
    if sys.version_info[0] == 3:
      # unicode in source file -> correct unicode in python string (ncf builder use case)
      result = ncf_rudder.canonify('héhé')
      self.assertEquals(result, "h__h__")


if __name__ == '__main__':
  unittest.main()
