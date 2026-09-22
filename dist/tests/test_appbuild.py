"""Reading the version out of the real app/build.gradle, and out of an APK."""

import os
import shutil
import tempfile
import unittest
import zipfile

from dist.lib import appbuild
from dist.tests import support

GRADLE = os.path.join(support.REPO_ROOT, "app", "build.gradle")


class GradleVersionTest(unittest.TestCase):

    def setUp(self):
        self.version = appbuild.read_gradle_version(GRADLE)

    def test_the_real_gradle_file_is_read(self):
        self.assertEqual(1429, self.version.version_code)
        self.assertEqual("5.0.0-beta.17", self.version.version_name)
        self.assertEqual(23, self.version.min_sdk)

    def test_the_debug_build_type_carries_a_version_name_suffix(self):
        self.assertEqual("-debug", self.version.suffixes["debug"])
        self.assertEqual("", self.version.suffixes["release"])
        self.assertEqual("5.0.0-beta.17-debug", self.version.version_name_for("debug"))
        self.assertEqual("5.0.0-beta.17", self.version.version_name_for("release"))

    def test_the_tag_and_asset_names(self):
        # One tag per version, shared by every build type; the build type is in
        # the asset name.
        self.assertEqual("v5.0.0-beta.17", self.version.tag())
        self.assertEqual("bbkb-5.0.0-beta.17-release.apk", self.version.asset_name("release"))
        self.assertEqual("bbkb-5.0.0-beta.17-debug.apk", self.version.asset_name("debug"))

    def test_the_suffix_is_read_from_the_right_block(self):
        # Brace-counted: a plain regex would read past the debug block's nested
        # braces and pick up the release block's value.
        text = """
        android {
          defaultConfig {
            versionCode = 7
            versionName = "1.2.3"
            minSdk = 23
            externalNativeBuild { cmake { arguments '-DX=ON' } }
          }
          buildTypes {
            debug {
              applicationIdSuffix = ".debug"
              something { nested = true }
              versionNameSuffix "-dbg"
            }
            release {
              versionNameSuffix "-rel"
            }
          }
        }
        """
        path = os.path.join(tempfile.mkdtemp(prefix="bbkb-gradle-"), "build.gradle")
        with open(path, "w") as handle:
            handle.write(text)
        try:
            version = appbuild.read_gradle_version(path)
            self.assertEqual(7, version.version_code)
            self.assertEqual("1.2.3", version.version_name)
            self.assertEqual("-dbg", version.suffixes["debug"])
            self.assertEqual("-rel", version.suffixes["release"])
        finally:
            shutil.rmtree(os.path.dirname(path), ignore_errors=True)


class ApkReadTest(unittest.TestCase):

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="bbkb-apk-")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def make_apk(self, name="app-release.apk", manifest=True):
        path = os.path.join(self.root, name)
        with zipfile.ZipFile(path, "w") as archive:
            if manifest:
                archive.writestr("AndroidManifest.xml", b"\x03\x00\x08\x00binary")
            archive.writestr("classes.dex", b"dex\n035\x00")
            archive.writestr("META-INF/CERT.RSA", b"signature")
        return path

    def test_without_aapt2_the_apk_is_accepted_with_a_clear_warning(self):
        # `repo_root` points at an empty dir, so there is no local.properties and
        # no SDK to find an aapt2 in.
        info = appbuild.read_apk(self.make_apk(), self.root)
        self.assertEqual("zipfile", info.source)
        self.assertIsNone(info.version_code)
        self.assertIn("aapt2 was not found", info.warning)
        self.assertIn("could NOT be verified", info.warning)
        self.assertIn("v1 signature block", info.warning)

    def test_a_file_that_is_not_an_apk_is_rejected(self):
        path = os.path.join(self.root, "not-an-apk.apk")
        with open(path, "w") as handle:
            handle.write("hello")
        with self.assertRaises(ValueError):
            appbuild.read_apk(path, self.root)

    def test_a_zip_without_an_android_manifest_is_rejected(self):
        path = self.make_apk(name="empty.apk", manifest=False)
        with self.assertRaises(ValueError):
            appbuild.read_apk(path, self.root)

    def test_sdk_dir_comes_from_local_properties(self):
        with open(os.path.join(self.root, "local.properties"), "w") as handle:
            handle.write("#comment\nsdk.dir=/somewhere/Android/sdk\n")
        self.assertEqual("/somewhere/Android/sdk", appbuild.sdk_dir(self.root))

    def test_no_sdk_dir_and_no_env_means_no_aapt2(self):
        saved = dict((key, os.environ.pop(key, None))
                     for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"))
        try:
            self.assertIsNone(appbuild.sdk_dir(self.root))
        finally:
            for key, value in saved.items():
                if value is not None:
                    os.environ[key] = value


if __name__ == "__main__":
    unittest.main()
