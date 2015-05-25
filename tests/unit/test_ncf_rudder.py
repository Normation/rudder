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

    self.test_reporting = os.path.realpath('test_technique_reporting.cf')
    self.reporting_content = open(self.test_reporting).read()
    self.reporting_metadata = ncf.parse_technique_metadata(self.reporting_content)
    reporting_method_calls = ncf.parse_technique_methods(self.test_reporting)
    self.reporting_metadata['method_calls'] = reporting_method_calls

    self.test_reporting_with_var = os.path.realpath('test_technique_with_variable.cf')
    self.reporting_with_var_content = open(self.test_reporting_with_var).read()
    self.reporting_with_var_metadata = ncf.parse_technique_metadata(self.reporting_with_var_content)
    reporting_with_var_method_calls = ncf.parse_technique_methods(self.test_reporting_with_var)
    self.reporting_with_var_metadata['method_calls'] = reporting_with_var_method_calls


    self.test_any_technique = os.path.realpath('test_technique_any.cf')
    self.any_technique_content = open(self.test_any_technique).read()
    self.any_technique_metadata = ncf.parse_technique_metadata(self.any_technique_content)
    any_technique_method_calls = ncf.parse_technique_methods(self.test_any_technique)
    self.any_technique_metadata['method_calls'] = any_technique_method_calls

    self.test_metadata_xml_file = os.path.realpath('test_metadata.xml')
    self.test_metadata_xml_content = open(self.test_metadata_xml_file).read()

  def test_expected_reports_from_technique(self):
    expected_reports_string = ncf_rudder.get_technique_expected_reports(self.technique_metadata)
    self.assertEquals(expected_reports_string, self.test_expected_reports_csv_content)

  def test_metadata_xml_from_technique(self):
    """The XML content generated from a ncf technique must match the XML content required by Rudder for it's metadata.xml file"""
    metadata_xml_string = ncf_rudder.get_technique_metadata_xml(self.technique_metadata)
    expected_metadata_pure_xml = self.test_metadata_xml_content
    self.assertEquals(expected_metadata_pure_xml, metadata_xml_string)

  def test_path_for_technique_dir(self):
    root_path = '/tmp/ncf_rudder_tests'
    path = ncf_rudder.get_path_for_technique(root_path, self.technique_metadata)
    self.assertEquals(os.path.join(root_path, 'bla', '0.1'), path)

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

    self.assertEquals(result, generated_result)


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

    self.assertEquals(result, generated_result)


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

    self.assertEquals(result, generated_result)

  # Other required functions:
  # ncf_rudder.write_technique_for_rudder(path, technique_metadata)
  # ncf_rudder.write_all_techniques_for_rudder(path)

if __name__ == '__main__':
  unittest.main()
