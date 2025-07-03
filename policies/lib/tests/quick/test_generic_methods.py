#!/usr/bin/python3
"""
Sanity test file for methods
"""

import sys
import os
DIRNAME = os.path.dirname(os.path.abspath(__file__))
TESTLIB_PATH = DIRNAME + '/../testlib'
sys.path.insert(0, TESTLIB_PATH)
import re
import unittest
import collections
import testlib
import avocado

class TestNcfBundles(avocado.Test):
#class TestNcfBundles(unittest.TestCase):
    """
    Sanity tests for methods
    """
    def setUp(self):
        """
        Tests setup
        """
        self.methods = testlib.get_methods()

    def test_methods_should_have_a_metadata(self):
        """
        Methods should have a metadata
        """
        for method in self.methods:
            with self.subTest(i=method.path):
                self.assertIn('name', method.metadata)
                self.assertIn('description', method.metadata)
                # We have way too much method without documentation at the moment
                # this will need a dedicated pr
                #self.assertIn('documentation', method.metadata)
                self.assertIn('parameter', method.metadata)
                self.assertIn('class_prefix', method.metadata)
                self.assertIn('class_parameter', method.metadata)

    def test_methods_name_should_be_unique(self):
        """
        Methods should @name should be unique
        """
        names = [x.metadata['name'] for x in self.methods]
        duplicates = [x for x, y in collections.Counter(names).items() if y > 1]
        if [] != duplicates:
            for method in self.methods:
                with self.subTest(i=method.path):
                    self.assertNotIn(method.metadata['name'], duplicates)

    @avocado.skip('Should be reenabled once replaced')
    def test_old_class_prefix(self):
        """
        Methods should define an old_class_prefix in either one of the following formats:
          "old_class_prefix" string => canonify("<class_prefix_from_metadata>_${<class_parameter_from_metadata>}");
          "old_class_prefix" string => "<class_prefix_from_metadata>_${canonified_<class_parameter_from_metadata>}";

        In fact, we should force the first one.
        """
        for method in self.methods:
            with self.subTest(k=method.path):
                class_prefix = method.metadata['class_prefix']
                class_parameter = method.metadata['class_parameter']

                class_pattern1 = r"\"old_class_prefix\"\s+string\s+=>\s+canonify\(\"" + class_prefix + "_" + r"\${" + class_parameter + r"}\"\);"
                class_pattern2 = r"\"old_class_prefix\"\s+string\s+=>\s+\"" + class_prefix + "_" + r"\${canonified_" + class_parameter + r"}\";"

                if not skip(method):
                    self.assertTrue(testlib.test_pattern_on_file(method.path, class_pattern1) is not None or testlib.test_pattern_on_file(method.path, class_pattern2) is not None)

    def test_methods_should_not_contain_unescaped_chars(self):
        """
        Test if the documentation fields contain unescaped dollar characters that would break pdflatex
        """
        for method in self.methods:
            check_backquotes = re.compile(r'[^\`]*\$[^\`]*')
            with self.subTest(i=method.path):
                if 'documentation' in method.metadata:
                    self.assertFalse(check_backquotes.match(method.metadata['documentation']))

    @avocado.skip('Lots of methods are not correct atm')
    def test_methods_name_should_be_class_prefix(self):
        """
        Methods prefix should be based on their name
        """
        for method in self.methods:
            with self.subTest(i=method.path):
                class_prefix = method.metadata['class_prefix']
                path = method.path_basename
                self.assertTrue(class_prefix == path, '\nprefix = %s\n  path = %s'%(class_prefix, path))


### Helper functions
def skip(method):
    """
    In some tests, we need to skip some methods, either because they are not really a true method
    and only a wrapper or because their inner logic make them exceptions
    """
    to_skip = ['file_from_shared_folder', 'user_password_hash']
    result = False
    if testlib.test_pattern_on_file(method.path, r'{\s+methods:\s+[^;]+;\s+}'):
        result = True
    elif method.path_basename in to_skip:
        result = True
    if result:
        pass
    return result

#if __name__ == '__main__':
#    #unittest.main()
#    main()
