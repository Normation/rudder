from rpkg import PluginVersion, WebappVersion, Rpkg
from distutils.version import LooseVersion, StrictVersion
import unittest
from parameterized import parameterized


class TestWebappVersion(unittest.TestCase):
    @parameterized.expand(
        [
            ['7.0.0~rc1-1.0', '7.0.0', 'c1'],
            ['7.0.0-1.0', '7.0.0', ''],
            ['6.2.11~git1234-1.01', '6.2.11', '~git1234'],
        ]
    )
    def test_parsing(self, x, xyz, mode):
        version = WebappVersion(x)
        self.assertEqual(version.get_xyz(), xyz)
        self.assertEqual(version.get_mode(), mode)

    @parameterized.expand(
        [
            ['7.0.0~rc1-1.0', '7.0.0~rc1-2.0'],
            ['7.0.0~alpha1-1.0', '7.0.0~alpha1-2.0'],
        ]
    )
    def test_equality(self, a, b):
        vleft = WebappVersion(a)
        vright = WebappVersion(b)
        self.assertEqual(vleft.get_xyz(), vright.get_xyz())
        self.assertEqual(vleft.get_mode(), vright.get_mode())
        self.assertEqual(vleft, vright)

    @parameterized.expand(
        [
            ['7.0.0~rc1-1.0', '7.0.0~rc2-1.0'],
            ['7.0.0~rc1-1.0', '7.0.1~rc1-1.0'],
            ['6.0.0~rc2-1.0', '7.0.1~rc1-1.0'],
            ['6.0.0~rc2-1.0', '7.0.0-1.0'],
            ['6.1.2-1.0', '6.2.0-1.0'],
            ['6.0.0~alpha-1.0', '6.0.0-1.0'],
            ['6.0.0~alpha2-1.0', '6.0.0~alpha3-1.0'],
        ]
    )
    def test_less(self, a, b):
        vleft = WebappVersion(a)
        vright = WebappVersion(b)
        self.assertLess(vleft, vright)


class TestPluginVersion(unittest.TestCase):
    @parameterized.expand(
        [
            ['7.0.0-1.0', '7.0.1-1.0'],
            ['7.0.0~alpha2-1.0', '7.0.0~alpha3-1.0'],
            ['7.0.0-1.0', '7.0.0-1.1'],
            ['6.3.2-1.0', '6.3.3-1.0'],
            ['6.3.2-1.0-nightly', '6.3.3-1.0'],
            ['6.0.0-1.0-SNAPSHOT', '6.0.0-1.0'],
            ['5.0-1.1-SNAPSHOT', '6.0.0-1.0'],
        ]
    )
    def test_less(self, a, b):
        self.assertLess(
            PluginVersion(a),
            PluginVersion(b),
            '{vleft} < {vright} could not be verified'.format(vleft=a, vright=b),
        )


class TestRpkg(unittest.TestCase):
    @parameterized.expand(
        [
            ['7.0.0-1.0', '7.0.1-1.0'],
            ['7.0.0~alpha2-1.0', '7.0.0~alpha3-1.0'],
            ['7.0.0-1.0', '7.0.0-1.1'],
            ['6.3.2-1.0', '6.3.3-1.0'],
            ['6.3.2-1.0-nightly', '6.3.3-1.0'],
            ['6.0.0-1.0-SNAPSHOT', '6.0.0-1.0'],
            ['5.0-1.1-SNAPSHOT', '6.0.0-1.0'],
        ]
    )
    def test_less(self, a, b):
        self.assertLess(
            Rpkg('long', 'short', 'path', PluginVersion(a), None),
            Rpkg('long', 'short', 'path', PluginVersion(b), None),
            '{vleft} < {vright} could not be verified'.format(vleft=a, vright=b),
        )


if __name__ == '__main__':
    unittest.main()
