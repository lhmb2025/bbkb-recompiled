"""The two CLIs, end to end, offline.

Each test runs the real script in a subprocess with an empty PATH, so neither
`gh` nor `aapt2` can be found: the publishers take their no-tool paths and
cannot reach GitHub even if something below were wrong. What a dry run may do
is compute hashes and (for packs) write `dist/manifest.json`; what it may never
do is create a release, upload an asset, or write an app entry.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

from dist.tests import support

PACKS = os.path.join(support.REPO_ROOT, "dist", "publish_packs.py")
APP = os.path.join(support.REPO_ROOT, "dist", "publish_app.py")

# A slice of the real catalogue: a plain language, a country-qualified pack, a
# Swiss variant from `new/`, a refused three-letter code, and the two files that
# collapse onto a base language another file already owns.
SOURCE_FILES = [
    "Blackberry_1305_r1-2_AFlsUN_xt9_ALM3.ldb",
    "Blackberry_1305_r1-76_ENubUN_xt9_ALM3.ldb",
    "Blackberry_1305_r1-17_ENubUNUK_xt9_ALM3.ldb",
    "Blackberry_1305_r1-3_ENubUNZH_xt9_2.ldb",
    "Blackberry_1305_r1-28_ESusUN_xt9_ALM3.ldb",
    "Blackberry_1305_r1-11_ESusUNlatam_xt9_ALM3.ldb",
]
NEW_FILES = [
    "Blackberry_1305_r1-20_DEusUNCH_xt9_ALM3.ldb",
    "Blackberry_1305_r1-2_BRXlsUN_xt9.ldb",
]

EXISTING_APP_SECTION = {
    "release": {
        "versionCode": 1429,
        "versionName": "5.0.0-beta.17",
        "url": "https://github.com/lhmb2025/bbkb-recompiled/releases/download/"
               "v5.0.0-beta.17/bbkb-5.0.0-beta.17-release.apk",
        "sha256": "c" * 64,
        "size": 4242,
        "minSdk": 23,
        "notes": "previously published",
    }
}


def run(argv):
    return subprocess.run([sys.executable] + argv, capture_output=True, text=True,
                          cwd=support.REPO_ROOT, env=support.empty_path_env())


class PublishPacksDryRunTest(unittest.TestCase):

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="bbkb-packs-dry-")
        self.source = os.path.join(self.root, "ldb")
        os.makedirs(os.path.join(self.source, "new"))
        for index, name in enumerate(SOURCE_FILES):
            with open(os.path.join(self.source, name), "wb") as handle:
                handle.write(b"ldb" + bytes([index]) * (1000 + index))
        for index, name in enumerate(NEW_FILES):
            with open(os.path.join(self.source, "new", name), "wb") as handle:
                handle.write(b"new" + bytes([index]) * 500)
        self.manifest = os.path.join(self.root, "manifest.json")
        with open(self.manifest, "w") as handle:
            json.dump({"schema": 1, "generated": "2026-01-01T00:00:00Z",
                       "app": EXISTING_APP_SECTION, "packs": {}}, handle)

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def publish(self, *extra):
        return run([PACKS, "--source", self.source, "--version", "1902.01",
                    "--manifest", self.manifest] + list(extra))

    def read_manifest(self):
        with open(self.manifest, "r") as handle:
            return json.load(handle)

    def test_a_dry_run_prints_the_decision_table_and_touches_nothing_on_github(self):
        result = self.publish("--dry-run")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        out = result.stdout

        self.assertIn("PUBLISH", out)
        self.assertIn("SKIP/refused", out)
        self.assertIn("SKIP/duplicate", out)
        self.assertIn("DRY RUN: nothing was created or uploaded", out)
        # The gh calls are printed, not run.
        self.assertIn("would run: gh release create packs-1902.01", out)
        self.assertIn("would run: gh release upload packs-1902.01", out)
        self.assertIn("--clobber", out)
        self.assertNotIn("running:", out)
        self.assertIn("scanned   8 files", out)
        self.assertIn("published 5", out)
        self.assertIn("1 refused, 0 unsupported, 2 duplicate", out)
        self.assertIn("de_CH->de", out)

    def test_dry_run_is_the_default_without_any_flag(self):
        result = self.publish()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("DRY RUN", result.stdout)
        self.assertIn("would run:", result.stdout)

    def test_upload_and_dry_run_together_resolve_to_a_dry_run(self):
        result = self.publish("--upload", "--dry-run")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("dry run wins", result.stdout)
        self.assertIn("DRY RUN", result.stdout)
        self.assertNotIn("running:", result.stdout)

    def test_the_manifest_gets_the_packs_section_and_keeps_the_app_section(self):
        self.publish("--dry-run")
        document = self.read_manifest()

        self.assertEqual(1, document["schema"])
        self.assertEqual(EXISTING_APP_SECTION, document["app"])
        packs = document["packs"]
        self.assertEqual("1902.01", packs["version"])
        self.assertEqual("https://github.com/lhmb2025/bbkb-recompiled/releases/download/"
                         "packs-1902.01/", packs["baseUrl"])
        self.assertEqual(5, len(packs["items"]))

        by_locale = dict((item["locale"], item) for item in packs["items"])
        self.assertEqual({"af", "en", "en_UK", "es", "de_CH"}, set(by_locale))
        self.assertEqual("Afrikaans", by_locale["af"]["name"])
        self.assertEqual("de", by_locale["de_CH"]["group"])
        self.assertEqual("German (Switzerland)", by_locale["de_CH"]["name"])
        self.assertNotIn("group", by_locale["af"])
        for item in packs["items"]:
            self.assertEqual(64, len(item["sha256"]))
            self.assertEqual(item["sha256"], item["sha256"].lower())
            self.assertGreater(item["size"], 0)
            self.assertTrue(item["file"].endswith(".ldb"))

    def test_the_sizes_and_hashes_are_the_files_own(self):
        import hashlib
        self.publish("--dry-run")
        items = dict((item["file"], item) for item in self.read_manifest()["packs"]["items"])
        path = os.path.join(self.source, "Blackberry_1305_r1-2_AFlsUN_xt9_ALM3.ldb")
        with open(path, "rb") as handle:
            payload = handle.read()
        item = items["Blackberry_1305_r1-2_AFlsUN_xt9_ALM3.ldb"]
        self.assertEqual(hashlib.sha256(payload).hexdigest(), item["sha256"])
        self.assertEqual(len(payload), item["size"])

    def test_re_running_an_unchanged_catalogue_leaves_the_manifest_byte_identical(self):
        self.publish("--dry-run")
        with open(self.manifest, "r") as handle:
            first = handle.read()
        second_run = self.publish("--dry-run")
        with open(self.manifest, "r") as handle:
            self.assertEqual(first, handle.read())
        self.assertIn("unchanged", second_run.stdout)

    def test_a_missing_source_folder_is_an_error_not_an_empty_release(self):
        result = run([PACKS, "--source", os.path.join(self.root, "nope"),
                      "--version", "1902.01", "--manifest", self.manifest])
        self.assertEqual(2, result.returncode)
        self.assertIn("does not exist", result.stdout)


class PublishAppDryRunTest(unittest.TestCase):

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="bbkb-app-dry-")
        self.apk = os.path.join(self.root, "app-release.apk")
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"\x03\x00\x08\x00binary")
            archive.writestr("classes.dex", b"dex\n035\x00")
            archive.writestr("META-INF/CERT.RSA", b"signature")
        self.manifest = os.path.join(self.root, "manifest.json")
        with open(self.manifest, "w") as handle:
            json.dump({"schema": 1, "generated": "2026-01-01T00:00:00Z", "app": {},
                       "packs": {"version": "1902.01", "baseUrl": "u", "items": []}}, handle)

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def publish(self, build_type, *extra):
        # --repo-root is the temp dir (no local.properties -> no aapt2), while
        # --gradle still points at the real app/build.gradle.
        return run([APP, "--apk", self.apk, "--build-type", build_type,
                    "--manifest", self.manifest, "--repo-root", self.root,
                    "--gradle", os.path.join(support.REPO_ROOT, "app", "build.gradle")]
                   + list(extra))

    def read_manifest(self):
        with open(self.manifest, "r") as handle:
            return json.load(handle)

    def test_a_release_dry_run_reports_the_version_tag_asset_and_url(self):
        result = self.publish("release", "--dry-run", "--notes", "beta 17")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        out = result.stdout
        self.assertIn("gradle    versionCode=1429 versionName=5.0.0-beta.17", out)
        self.assertIn("release v5.0.0-beta.17", out)
        self.assertIn("bbkb-5.0.0-beta.17-release.apk", out)
        self.assertIn("releases/download/v5.0.0-beta.17/bbkb-5.0.0-beta.17-release.apk", out)
        self.assertIn("would run: gh release create v5.0.0-beta.17", out)
        self.assertIn("DRY RUN", out)
        self.assertNotIn("running:", out)

    def test_without_aapt2_the_version_check_warns_instead_of_claiming_a_match(self):
        result = self.publish("release", "--dry-run")
        self.assertIn("WARNING:", result.stdout)
        self.assertIn("could NOT be verified", result.stdout)

    def test_a_dry_run_never_writes_the_manifest(self):
        before = self.read_manifest()
        self.publish("release", "--dry-run")
        self.assertEqual(before, self.read_manifest())

    def test_a_debug_build_cannot_be_uploaded(self):
        result = self.publish("debug", "--upload")
        self.assertEqual(2, result.returncode)
        self.assertIn("refusing to upload a debug build", result.stdout)
        self.assertIn("no app.debug entry", result.stdout)
        self.assertEqual({}, self.read_manifest()["app"])

    def test_a_debug_dry_run_says_it_is_not_a_published_channel(self):
        result = self.publish("debug", "--dry-run")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("not a published channel", result.stdout)
        self.assertIn("5.0.0-beta.17-debug", result.stdout)
        self.assertIn("bbkb-5.0.0-beta.17-debug.apk", result.stdout)
        self.assertEqual({}, self.read_manifest()["app"])

    def test_a_missing_apk_is_an_error(self):
        result = run([APP, "--apk", os.path.join(self.root, "nope.apk"),
                      "--build-type", "release", "--manifest", self.manifest,
                      "--repo-root", self.root,
                      "--gradle", os.path.join(support.REPO_ROOT, "app", "build.gradle")])
        self.assertEqual(2, result.returncode)
        self.assertIn("no such APK", result.stdout)


if __name__ == "__main__":
    unittest.main()
