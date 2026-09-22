"""Two publishers, one file: neither may erase the other's section."""

import hashlib
import json
import os
import shutil
import tempfile
import unittest

from dist.lib import hashing
from dist.lib import manifest as manifest_lib


class ManifestMergeTest(unittest.TestCase):

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="bbkb-manifest-")
        self.path = os.path.join(self.root, "manifest.json")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def read(self):
        with open(self.path, "r") as handle:
            return json.load(handle)

    def test_a_missing_manifest_loads_as_schema_1_with_an_empty_app(self):
        document = manifest_lib.load(self.path)
        self.assertEqual(1, document["schema"])
        self.assertEqual({}, document["app"])
        self.assertEqual({}, document["packs"])

    def test_writing_packs_preserves_the_app_section(self):
        app = {"release": {"versionCode": 1429, "versionName": "5.0.0-beta.17",
                           "url": "https://example.invalid/a.apk", "sha256": "a" * 64,
                           "size": 1, "minSdk": 23, "notes": "n"}}
        manifest_lib.save(self.path, manifest_lib.merge_section(manifest_lib.load(self.path),
                                                                "app", app))
        packs = {"version": "1902.01", "baseUrl": "https://example.invalid/", "items": [
            {"locale": "af", "name": "Afrikaans", "file": "a.ldb", "sha256": "b" * 64, "size": 2}]}
        previous = manifest_lib.load(self.path)
        manifest_lib.save(self.path, manifest_lib.merge_section(previous, "packs", packs), previous)

        document = self.read()
        self.assertEqual(app, document["app"])
        self.assertEqual(packs, document["packs"])

    def test_writing_the_app_preserves_the_packs_section(self):
        packs = {"version": "1902.01", "baseUrl": "https://example.invalid/", "items": [
            {"locale": "af", "name": "Afrikaans", "file": "a.ldb", "sha256": "b" * 64, "size": 2}]}
        manifest_lib.save(self.path, manifest_lib.merge_section(manifest_lib.load(self.path),
                                                               "packs", packs))
        previous = manifest_lib.load(self.path)
        app = dict(previous["app"])
        app["release"] = {"versionCode": 1429}
        manifest_lib.save(self.path, manifest_lib.merge_section(previous, "app", app), previous)

        document = self.read()
        self.assertEqual(packs, document["packs"])
        self.assertEqual({"release": {"versionCode": 1429}}, document["app"])

    def test_the_key_order_is_schema_generated_app_packs(self):
        manifest_lib.save(self.path, manifest_lib.empty_manifest())
        with open(self.path, "r") as handle:
            document = json.load(handle)
        self.assertEqual(["schema", "generated", "app", "packs"], list(document))

    def test_rewriting_the_same_content_is_a_no_op(self):
        packs = {"version": "1902.01", "baseUrl": "u", "items": []}
        document = manifest_lib.merge_section(manifest_lib.load(self.path), "packs", packs)
        self.assertTrue(manifest_lib.save(self.path, document))
        with open(self.path, "r") as handle:
            first = handle.read()

        previous = manifest_lib.load(self.path)
        again = manifest_lib.merge_section(previous, "packs", packs)
        self.assertFalse(manifest_lib.save(self.path, again, previous),
                         "an unchanged catalogue must not rewrite the manifest")
        with open(self.path, "r") as handle:
            self.assertEqual(first, handle.read())

    def test_a_changed_catalogue_does_rewrite(self):
        document = manifest_lib.merge_section(manifest_lib.load(self.path), "packs",
                                              {"version": "1902.01", "baseUrl": "u", "items": []})
        manifest_lib.save(self.path, document)
        previous = manifest_lib.load(self.path)
        changed = manifest_lib.merge_section(previous, "packs",
                                            {"version": "1902.02", "baseUrl": "u", "items": []})
        self.assertTrue(manifest_lib.save(self.path, changed, previous))
        self.assertEqual("1902.02", self.read()["packs"]["version"])

    def test_the_urls_are_the_contracted_ones(self):
        self.assertEqual(
            "https://github.com/lhmb2025/bbkb-recompiled/releases/download/packs-1902.01/",
            manifest_lib.packs_base_url("1902.01"))
        self.assertEqual(
            "https://github.com/lhmb2025/bbkb-recompiled/releases/download/"
            "v5.0.0-beta.18/bbkb-5.0.0-beta.18-release.apk",
            manifest_lib.app_asset_url("5.0.0-beta.18", "bbkb-5.0.0-beta.18-release.apk"))


class HashingTest(unittest.TestCase):

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="bbkb-hash-")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def write(self, name, payload):
        path = os.path.join(self.root, name)
        with open(path, "wb") as handle:
            handle.write(payload)
        return path

    def test_sha256_of_the_published_test_vectors(self):
        self.assertEqual(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hashing.sha256_file(self.write("empty", b"")))
        self.assertEqual(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hashing.sha256_file(self.write("abc", b"abc")))

    def test_chunking_does_not_change_the_digest_across_the_1mib_boundary(self):
        payload = (b"bbkb" * 777)[:3] + os.urandom(3 * 1024 * 1024 + 17)
        path = self.write("big", payload)
        self.assertEqual(hashlib.sha256(payload).hexdigest(), hashing.sha256_file(path))
        self.assertEqual(len(payload), hashing.size_of(path))

    def test_digest_and_size_together(self):
        path = self.write("pair", b"x" * 100)
        digest, size = hashing.digest_and_size(path)
        self.assertEqual(hashlib.sha256(b"x" * 100).hexdigest(), digest)
        self.assertEqual(100, size)
        self.assertEqual(digest, digest.lower())

    def test_human_size_is_only_for_printing(self):
        self.assertEqual("512 B", hashing.human_size(512))
        self.assertEqual("1.0 KB", hashing.human_size(1024))
        self.assertEqual("4.2 MB", hashing.human_size(4400000))


if __name__ == "__main__":
    unittest.main()
