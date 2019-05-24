from rpkg import PluginVersion
import unittest
from hypothesis import given, strategies as st

class TestEncoding(unittest.TestCase):

    # any version equals themselves
    @given(st.from_regex(r"[0-9]+\.[0-9]+\-[0-9]+\.[0-9]+(\-SNAPSHOT)?", fullmatch=True))
    def test_equality(self, v):
        self.assertTrue(PluginVersion(v) == PluginVersion(v))
        self.assertFalse(PluginVersion(v) != PluginVersion(v))
        self.assertFalse(PluginVersion(v) < PluginVersion(v))
        self.assertFalse(PluginVersion(v) > PluginVersion(v))
        self.assertGreaterEqual(PluginVersion(v), PluginVersion(v))
        self.assertLessEqual(PluginVersion(v), PluginVersion(v))

    # nightly is inferior to release at equal version
    @given(st.from_regex(r"[0-9]+\.[0-9]+\-[0-9]+\.[0-9]+", fullmatch=True))
    def test_release_gt_nightly(self, v):
        self.assertGreater(PluginVersion(v), PluginVersion(v + "-SNAPSHOT"))
        self.assertLess(PluginVersion(v + "-SNAPSHOT"), PluginVersion(v))
        self.assertTrue(PluginVersion(v + "-SNAPSHOT") != PluginVersion(v))

    # W.X-Y.Z{-SNAPSHOT} < W+1.X-Y-Z{-SNAPSHOT}
    @given(st.integers(min_value=0), st.integers(min_value=0), st.integers(min_value=0), st.integers(min_value=0), st.integers(min_value=1), st.from_regex(r"(\-SNAPSHOT)?", fullmatch=True), st.from_regex(r"(\-SNAPSHOT)?", fullmatch=True))
    def test_rudder_version_priority1(self, W, X, Y, Z, randDiff, mode1, mode2):
        v1 = str(W) + "." + str(X) + "-" + str(Y) + "." + str(Z) + mode1
        v2 = str(W + randDiff) + "." + str(X) + "-" + str(Y) + "." + str(Z) + mode2
        self.assertGreater(PluginVersion(v2), PluginVersion(v1))
        self.assertLess(PluginVersion(v1), PluginVersion(v2))
        self.assertTrue(PluginVersion(v1) != PluginVersion(v2))

    # W.X-Y.Z{-SNAPSHOT} < W.X+1-Y-Z{-SNAPSHOT}
    @given(st.integers(min_value=0), st.integers(min_value=0), st.integers(min_value=0), st.integers(min_value=0), st.integers(min_value=1), st.from_regex(r"(\-SNAPSHOT)?", fullmatch=True), st.from_regex(r"(\-SNAPSHOT)?", fullmatch=True))
    def test_rudder_version_priority2(self, W, X, Y, Z, randDiff, mode1, mode2):
        v1 = str(W) + "." + str(X) + "-" + str(Y) + "." + str(Z) + mode1
        v2 = str(W) + "." + str(X + randDiff) + "-" + str(Y) + "." + str(Z) + mode2
        self.assertGreater(PluginVersion(v2), PluginVersion(v1))
        self.assertLess(PluginVersion(v1), PluginVersion(v2))
        self.assertTrue(PluginVersion(v1) != PluginVersion(v2))

    # W.X-Y.Z{-SNAPSHOT} < W.X-Y+1-Z{-SNAPSHOT}
    @given(st.integers(min_value=0), st.integers(min_value=0), st.integers(min_value=0), st.integers(min_value=0), st.integers(min_value=1), st.from_regex(r"(\-SNAPSHOT)?", fullmatch=True), st.from_regex(r"(\-SNAPSHOT)?", fullmatch=True))
    def test_plugin_version_priority1(self, W, X, Y, Z, randDiff, mode1, mode2):
        v1 = str(W) + "." + str(X) + "-" + str(Y) + "." + str(Z) + mode1
        v2 = str(W) + "." + str(X) + "-" + str(Y + randDiff) + "." + str(Z) + mode2
        self.assertGreater(PluginVersion(v2), PluginVersion(v1))
        self.assertLess(PluginVersion(v1), PluginVersion(v2))
        self.assertTrue(PluginVersion(v1) != PluginVersion(v2))

    # W.X-Y.Z{-SNAPSHOT} < W.X-Y-Z+1{-SNAPSHOT}
    @given(st.integers(min_value=0), st.integers(min_value=0), st.integers(min_value=0), st.integers(min_value=0), st.integers(min_value=1), st.from_regex(r"(\-SNAPSHOT)?", fullmatch=True), st.from_regex(r"(\-SNAPSHOT)?", fullmatch=True))
    def test_plugin_version_priority2(self, W, X, Y, Z, randDiff, mode1, mode2):
        v1 = str(W) + "." + str(X) + "-" + str(Y) + "." + str(Z) + mode1
        v2 = str(W) + "." + str(X) + "-" + str(Y) + "." + str(Z + randDiff) + mode2
        self.assertGreater(PluginVersion(v2), PluginVersion(v1))
        self.assertLess(PluginVersion(v1), PluginVersion(v2))
        self.assertTrue(PluginVersion(v1) != PluginVersion(v2))

    # W.X-Y.Z{-SNAPSHOT} < W+iw.X+ix-Y+iy-Z+iz{-SNAPSHOT}
    # Except if iw, ix, iy, iz = 0 and the first version is a release one
    @given(st.integers(min_value=0), st.integers(min_value=0), st.integers(min_value=0), st.integers(min_value=0), st.integers(min_value=0), st.integers(min_value=0), st.integers(min_value=0), st.integers(min_value=0), st.from_regex(r"(\-SNAPSHOT)?", fullmatch=True), st.from_regex(r"(\-SNAPSHOT)?", fullmatch=True))
    def test_global_compare(self, W, X, Y, Z, iw, ix, iy, iz, mode1, mode2):
        v1 = str(W) + "." + str(X) + "-" + str(Y) + "." + str(Z) + mode1
        v2 = str(W + iw) + "." + str(X + ix) + "-" + str(Y + iy) + "." + str(Z + iz) + mode2
         
        # Special reverse case where just the mode change
        if iw == ix == iy == iz == 0 and mode1 == "":
          self.assertGreaterEqual(PluginVersion(v1), PluginVersion(v2))
          self.assertLessEqual(PluginVersion(v2), PluginVersion(v1))
        else:
          self.assertGreaterEqual(PluginVersion(v2), PluginVersion(v1))
          self.assertLessEqual(PluginVersion(v1), PluginVersion(v2))

if __name__ == '__main__':
    unittest.main()

