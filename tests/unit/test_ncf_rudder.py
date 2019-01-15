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
    
    self.test_technique_file = os.path.realpath('test_technique.cf')
    with open(self.test_technique_file) as fd:
      self.technique_content = fd.read()
    self.all_methods = ncf.get_all_generic_methods_metadata()["data"]["generic_methods"]  
    self.technique_metadata = ncf.parse_technique_metadata(self.technique_content)['result']
    method_calls = ncf.parse_technique_methods(self.test_technique_file, self.all_methods)
    self.technique_metadata['method_calls'] = method_calls

    self.test_reporting = os.path.realpath('test_technique_reporting.cf')
    with open(self.test_reporting) as fd:
      self.reporting_content = fd.read()
    self.reporting_metadata = ncf.parse_technique_metadata(self.reporting_content)['result']
    reporting_method_calls = ncf.parse_technique_methods(self.test_reporting, self.all_methods)
    self.reporting_metadata['method_calls'] = reporting_method_calls

    self.test_reporting_with_var = os.path.realpath('test_technique_with_variable.cf')
    with open(self.test_reporting_with_var) as fd:
      self.reporting_with_var_content = fd.read()
    self.reporting_with_var_metadata = ncf.parse_technique_metadata(self.reporting_with_var_content)['result']
    reporting_with_var_method_calls = ncf.parse_technique_methods(self.test_reporting_with_var, self.all_methods)
    self.reporting_with_var_metadata['method_calls'] = reporting_with_var_method_calls


    self.test_any_technique = os.path.realpath('test_technique_any.cf')
    with open(self.test_any_technique) as fd:
      self.any_technique_content = fd.read()
    self.any_technique_metadata = ncf.parse_technique_metadata(self.any_technique_content)['result']
    any_technique_method_calls = ncf.parse_technique_methods(self.test_any_technique, self.all_methods)
    self.any_technique_metadata['method_calls'] = any_technique_method_calls

    self.rudder_reporting_file = os.path.realpath('test_technique_rudder_reporting.cf')
    with open(self.rudder_reporting_file) as fd:
      self.rudder_reporting_content = fd.read()

    # Testing Techniques with quote
    self.test_technique_with_quote_file = os.path.realpath('test_technique_with_quote.cf')
    with open(self.test_technique_with_quote_file) as fd:
      self.technique_with_quote_content = fd.read()
    self.technique_with_quote_metadata = ncf.parse_technique_metadata(self.technique_with_quote_content)['result']
    method_with_quote_calls = ncf.parse_technique_methods(self.test_technique_with_quote_file, self.all_methods)
    self.technique_with_quote_metadata['method_calls'] = method_with_quote_calls

  def test_rudder_reporting_from_technique(self):
    """The rudder-reporting.cf content generated from a ncf technique must match the reporting we expect for our technique"""
    rudder_reporting_string = ncf_rudder.generate_rudder_reporting(self.technique_metadata)
    expected_rudder_reporting = self.rudder_reporting_content
    self.assertEqual(expected_rudder_reporting.split("\n"), rudder_reporting_string.split("\n"))


  def test_path_for_technique_dir(self):
    root_path = '/tmp/ncf_rudder_tests'
    path = ncf_rudder.get_path_for_technique(root_path, self.technique_metadata)
    self.assertEqual(os.path.join(root_path, 'bla', '0.1'), path)

  def test_rudder_reporting_file(self):
    root_path = '/tmp/ncf_rudder_tests_reporting'
    ncf_rudder.write_technique_for_rudder(root_path, self.reporting_metadata)
    result = os.path.exists(os.path.realpath(os.path.join(root_path, 'bla', '0.1', "rudder_reporting.cf")))
    self.assertTrue(result)

  def test_any_technique_reporting_file(self):
    root_path = '/tmp/ncf_rudder_tests_any'
    ncf_rudder.write_technique_for_rudder(root_path, self.any_technique_metadata)
    result = not os.path.exists(os.path.realpath(os.path.join(root_path, 'bla', '0.1', "rudder_reporting.cf")))
    self.assertTrue(result)

  def test_rudder_reporting_content(self):

    expected_result = []
    expected_result.append('bundle agent bla_rudder_reporting')
    expected_result.append('{')
    expected_result.append('  vars:')
    expected_result.append('    "promisers"          slist => { @{this.callers_promisers}, cf_null }, policy => "ifdefined";')
    expected_result.append('    "class_prefix"      string => canonify(join("_", "promisers"));')
    expected_result.append('    "args"               slist => { };')
    expected_result.append('')
    expected_result.append('  methods:')
    expected_result.append('')
    expected_result.append('    !(debian)::')
    expected_result.append('      "dummy_report" usebundle => _classes_noop("service_start_apache2");')
    expected_result.append('      "dummy_report_1" usebundle => _method_reporting_context("ph2", "apache2");')
    expected_result.append('      "dummy_report" usebundle => log_rudder("Service start apache2 if debian", "apache2", "service_start_apache2", "${class_prefix}", @{args});')
    expected_result.append('')
    expected_result.append('    !(service_start_apache2_repaired)::')
    expected_result.append('      "dummy_report" usebundle => _classes_noop("package_install_openssh_server");')
    expected_result.append('      "dummy_report_2" usebundle => _method_reporting_context("ph3", "openssh-server");')
    expected_result.append('      "dummy_report" usebundle => log_rudder("Package install openssh-server if service_start_apache2_repaired", "openssh-server", "package_install_openssh_server", "${class_prefix}", @{args});')
    expected_result.append('')
    expected_result.append('    !(!service_start_apache2_repaired.debian)::')
    expected_result.append('      "dummy_report" usebundle => _classes_noop("command_execution__bin_date");')
    expected_result.append('      "dummy_report_3" usebundle => _method_reporting_context("ph4", "/bin/date");')
    expected_result.append('      "dummy_report" usebundle => log_rudder("Command execution /bin/date if !service_start_apache2_repaired.debian", "/bin/date", "command_execution__bin_date", "${class_prefix}", @{args});')
    expected_result.append('}')

    # Join all lines with \n
    result = '\n'.join(expected_result)+"\n"

    generated_result = ncf_rudder.generate_rudder_reporting(self.reporting_metadata)

    diff = self._unidiff_output(result, generated_result)

    if len(diff):
        print()
        print("Test test_rudder_reporting_content failed, this is the diff between expected and actual result:")
        print(diff)

    self.assertEqual(result, generated_result)


  def _unidiff_output(self, expected, actual):
    """
    Helper function. Returns a string containing the unified diff of two multiline strings.
    """

    import difflib
    expected=expected.splitlines(1)
    actual=actual.splitlines(1)

    diff=difflib.unified_diff(expected, actual)

    return ''.join(diff)

  def test_rudder_reporting_with_variable_content(self):


    expected_result = []
    expected_result.append('bundle agent Test_technique_with_variable_rudder_reporting')
    expected_result.append('{')
    expected_result.append('  vars:')
    expected_result.append('    "promisers"          slist => { @{this.callers_promisers}, cf_null }, policy => "ifdefined";')
    expected_result.append('    "class_prefix"      string => canonify(join("_", "promisers"));')
    expected_result.append('    "args"               slist => { };')
    expected_result.append('')
    expected_result.append('  methods:')
    expected_result.append('')
    expected_result.append('      "dummy_report" usebundle => _classes_noop("file_create_${sys.workdir}_module_env"),')
    expected_result.append('                    ifvarclass => concat("!(directory_create_",canonify("${sys.workdir}"),"_module_repaired)");')
    expected_result.append('      "dummy_report_1" usebundle => _method_reporting_context("ph2", "${sys.workdir}/module/env"),')
    expected_result.append('                    ifvarclass => concat("!(directory_create_",canonify("${sys.workdir}"),"_module_repaired)");')
    expected_result.append('      "dummy_report" usebundle => log_rudder("File create ${sys.workdir}/module/env if directory_create_${sys.workdir}_module_repaired", "${sys.workdir}/module/env", "file_create_${sys.workdir}_module_env", "${class_prefix}", @{args}),')
    expected_result.append('                    ifvarclass => concat("!(directory_create_",canonify("${sys.workdir}"),"_module_repaired)");')
    expected_result.append('}')

    # Join all lines with \n
    result = '\n'.join(expected_result)+"\n"

    generated_result = ncf_rudder.generate_rudder_reporting(self.reporting_with_var_metadata)

    diff = self._unidiff_output(result, generated_result)

    if len(diff):
        print()
        print("Test test_rudder_reporting_with_variable_content failed, this is the diff between expected and actual result:")
        print(diff)

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
    result = ncf.canonify("ascii @&_ string")
    self.assertEqual(result, "ascii_____string")

    # python/ncf reads UTF-8 files and produces u'' strings in python2 and '' strings in python3
    # python2 tests
    if sys.version_info[0] == 2:
      # unicode in source file -> interpreted as unicode with u'' -> correct iso in python string (ncf builder use case)
      result = ncf.canonify(u'héhé')
      self.assertEqual(result, 'h__h__')

    # python3 tests
    if sys.version_info[0] == 3:
      # unicode in source file -> correct unicode in python string (ncf builder use case)
      result = ncf.canonify('héhé')
      self.assertEqual(result, "h__h__")


if __name__ == '__main__':
  unittest.main()
