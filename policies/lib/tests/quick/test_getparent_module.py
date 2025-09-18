import unittest, os, sys, shutil, tempfile
from pathlib import Path

DIRNAME = os.path.dirname(os.path.abspath(__file__))
GIT_ROOT = DIRNAME + '/../..'
sys.path.insert(0, GIT_ROOT + '/tree/10_ncf_internals/modules/promises/')
import getParent


class TestGetParents(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp()
        test_tree = os.path.dirname(os.path.abspath(__file__)) + "/assets/getParent"
        shutil.copytree(test_tree, self.test_dir + '/', dirs_exist_ok=True, symlinks=True)

    def tearDown(self):
        shutil.rmtree(self.test_dir)

    def test_module(self):
        param_list = [
            # 1 standard case
            (
                self.test_dir + '/1/2/3/emptyfile.txt',
                [
                    self.test_dir + '/1',
                    self.test_dir + '/1/2',
                    self.test_dir + '/1/2/3'
                ]
            ),
            # 2 must follow symlink
            (
                self.test_dir + '/1/2/3/symlink_to_2/3/emptyfile.txt',
                [
                    self.test_dir + '/1',
                    self.test_dir + '/1/2',
                    self.test_dir + '/1/2/3'
                ]
            ),
            # 3 must support globbing
            (
                self.test_dir + '/1/2/3/*',
                [
                    self.test_dir + '/1',
                    self.test_dir + '/1/2',
                    self.test_dir + '/1/2/3'
                ]
            )
        ]
        for path, expected in param_list:
            with self.subTest():
                # Since the tempdir can not be known before execution, assume it is correctly parsed and
                # start the index after the tempdir
                results = getParent.exec_module(path)
                self.assertEqual(results[1 + results.index(self.test_dir):], expected)
                self.assertNotIn('/', results)

    def test_module_on_non_existing_files_must_return_empty_list(self):
        results = getParent.exec_module(self.test_dir + '/file_that_does_not_exist.txt')
        self.assertEqual(results, [])

if __name__ == '__main__':
    unittest.main()
