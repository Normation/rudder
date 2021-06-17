import rudderPkgUtils
import unittest
import os
import shutil
import tempfile
import hashlib

def md5sum(fname):
    hash_md5 = hashlib.md5()
    with open(fname, "rb") as f:
        for chunk in iter(lambda: f.read(4096), b""):
            hash_md5.update(chunk)
    return hash_md5.hexdigest()

class TemporaryDirectory(object):
    """Context manager for tempfile.mkdtemp() so it's usable with "with" statement in python 2.7+."""
    def __enter__(self):
        self.name = tempfile.mkdtemp()
        return self.name

    def __exit__(self, exc_type, exc_value, traceback):
        shutil.rmtree(self.name)

class TestRun(unittest.TestCase):
    def test_simple_commands(self):
        """
        It should be able to run simple commands, without any capture of their outputs
        """
        self.assertEqual(rudderPkgUtils.run("/bin/true"), (0, None, None))
        self.assertNotEqual(rudderPkgUtils.run("/bin/false")[0], 0)

        self.assertEqual(rudderPkgUtils.run(["echo","'bob'"]), (0, None, None))

    def test_capture_output_stdout(self):
        """
        It should be able to capture stdout if needed
        """
        ret = rudderPkgUtils.run(['echo', 'bob'], capture_output=True)
        self.assertEqual(ret[0], 0)
        self.assertEqual(ret[1].decode('utf-8'), "bob\n")
        self.assertEqual(ret[2].decode('utf-8'), "" )

    def test_capture_output_stderr(self):
        """
        It should be able to capture stderr if needed
        """
        ret = rudderPkgUtils.run(['cat', 'filethatdonotexists.something'], capture_output=True)
        self.assertEqual(ret[0], 1)
        self.assertEqual(ret[1].decode('utf-8'), "")
        self.assertNotEqual(ret[2].decode('utf-8'), "")

    def test_exit_on_failure(self):
        """
        It should exit on failure if asked for
        """
        rudderPkgUtils.run(['/bin/false'], check=False)
        try:
            rudderPkgUtils.run(['/bin/false'], check=True)
            self.assertTrue(False)
        except:
            self.assertTrue(True)


class TestExtract(unittest.TestCase):
    def test_metada(self):
        rpkgPath = os.path.dirname(os.path.realpath(__file__)) + "/assets/helloworld.rpkg"
        with TemporaryDirectory() as tempDir:
            self.assertEqual(
                rudderPkgUtils.extract_archive_from_rpkg(rpkgPath, tempDir, "files.txz"),
                "helloworld/\nhelloworld/helloworld.jar\n"
            )
            self.assertEqual(md5sum(tempDir + '/helloworld/helloworld.jar'), '32b3331429ff42ab8b6f84c1f6db2fa1')

if __name__ == '__main__':
    unittest.main()

