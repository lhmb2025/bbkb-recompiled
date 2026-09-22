"""The publishing decisions, over the whole real catalogue, from names alone."""

import unittest

from dist.lib import catalogue
from dist.lib.ldb_names import EngineTable
from dist.tests import support


class CatalogueDecisionTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.table = EngineTable.load(support.REPO_ROOT)
        cls.rows = support.catalogue_rows()
        cls.decisions = catalogue.classify_names([r[0] for r in cls.rows], cls.table)
        cls.by_name = dict((d.file_name, d) for d in cls.decisions)

    def test_every_recorded_decision_still_holds(self):
        for file_name, _, expected in self.rows:
            self.assertEqual(expected, self.by_name[file_name].status, file_name)

    def test_the_tally(self):
        tally = catalogue.counts(self.decisions)
        self.assertEqual(109, tally[catalogue.PUBLISHED])
        self.assertEqual(7, tally[catalogue.REFUSED])
        self.assertEqual(0, tally[catalogue.DUPLICATE])
        self.assertEqual(0, tally[catalogue.UNSUPPORTED])
        self.assertEqual(116, len(self.decisions))

    def test_every_engine_table_locale_now_has_a_pack(self):
        # 105 table locales + the 4 region variants the table has no entry for.
        published = set(d.locale for d in self.decisions if d.published)
        self.assertEqual(set(), set(self.table.names) - published)
        self.assertEqual(109, len(published))

    def test_no_two_published_files_claim_the_same_locale(self):
        locales = [d.locale for d in self.decisions if d.published]
        self.assertEqual(len(locales), len(set(locales)))

    def test_every_published_locale_is_loadable_by_the_engine(self):
        for decision in self.decisions:
            if not decision.published:
                continue
            if decision.locale in self.table:
                continue
            # The only other way to be published: a region variant whose BASE
            # language the engine knows.
            self.assertIsNotNone(decision.group, decision.file_name)
            self.assertIn(decision.group, self.table, decision.file_name)

    def test_the_four_swiss_and_belgian_variants_are_grouped_under_their_base(self):
        variants = dict((d.locale, d) for d in self.decisions if d.published and d.group)
        self.assertEqual({"de_CH", "fr_CH", "it_CH", "nl_BE"}, set(variants))
        self.assertEqual("de", variants["de_CH"].group)
        self.assertEqual("German (Switzerland)", variants["de_CH"].name)
        self.assertEqual("nl", variants["nl_BE"].group)
        self.assertEqual("Dutch (Belgium)", variants["nl_BE"].name)

    def test_names_of_table_locales_come_from_the_table(self):
        decision = self.by_name["Blackberry_1305_r1-17_ENubUNUK_xt9_ALM3.ldb"]
        self.assertEqual("English (United Kingdom)", decision.name)
        self.assertIsNone(decision.group)

    # -- the skips ---------------------------------------------------------

    def test_the_seven_unsupported_languages_are_refused_with_a_reason(self):
        refused = [d for d in self.decisions if d.status == catalogue.REFUSED]
        self.assertEqual(7, len(refused))
        for decision in refused:
            self.assertIsNone(decision.locale)
            self.assertIn("locale table", decision.reason)
        three_letter = [d for d in refused if "three-letter" in d.reason]
        self.assertEqual(5, len(three_letter))

    def test_nothing_in_the_catalogue_is_a_duplicate_any_more(self):
        self.assertEqual([], [d.file_name for d in self.decisions
                              if d.status == catalogue.DUPLICATE])

    # -- the table-locale overrides ----------------------------------------

    def test_the_four_override_files_are_published_under_the_tables_locale(self):
        expected = {
            "Blackberry_1305_r1-11_ESusUNlatam_xt9_ALM3.ldb":
                ("es_419", "Spanish (Latin America)", "es"),
            "Blackberry_1305_r1-3_ENubUNZH_xt9_2.ldb":
                ("en_ZH", "English (China)", "en"),
            "Blackberry_1305_r1-15-2-6_ZHtbUNps_Big5HKSCS_bpmf_pinyin_CJ_xt9_bigTW_ALM.ldb":
                ("zh_TW", "Chinese Taiwan", "zh"),
            "Blackberry_1305_r1-22-2-5_ZHtbUNps_Big5HKSCS_bpmf_pinyin_CJ_xt9_big_ALM.ldb":
                ("zh_HK", "Chinese Hongkong", "zh"),
        }
        self.assertEqual(set(expected), set(catalogue.TABLE_LOCALE_OVERRIDES))
        for file_name, (locale, name, parsed) in expected.items():
            decision = self.by_name[file_name]
            self.assertEqual(catalogue.PUBLISHED, decision.status, file_name)
            self.assertEqual(locale, decision.locale, file_name)
            self.assertEqual(name, decision.name, file_name)
            self.assertEqual(parsed, decision.parsed_locale, file_name)
            self.assertTrue(decision.overridden, file_name)
            # An override is a locale the table HAS; it never invents one.
            self.assertIn(locale, self.table, file_name)
            self.assertIsNone(decision.group, file_name)
            self.assertIn("not the filename", decision.reason)

    def test_the_base_language_packs_keep_their_own_locales(self):
        # The point of the overrides: the four no longer collide with these.
        for file_name, locale in (
                ("Blackberry_1305_r1-76_ENubUN_xt9_ALM3.ldb", "en"),
                ("Blackberry_1305_r1-28_ESusUN_xt9_ALM3.ldb", "es"),
                ("Blackberry_1305_r1-17-10-3_ZHsbUNps_GB2312_xt9_big_ALM.ldb", "zh")):
            decision = self.by_name[file_name]
            self.assertTrue(decision.published, file_name)
            self.assertEqual(locale, decision.locale, file_name)
            self.assertFalse(decision.overridden, file_name)

    def test_an_override_the_engine_table_lacks_is_ignored(self):
        # The guard that keeps the map honest: with es_419 gone from the table,
        # the latam pack falls back to its parsed locale and loses to plain
        # Spanish again, rather than being published under a locale that
        # cannot load.
        trimmed = EngineTable([e for e in self.table.entries
                               if not (e["language"] == "es" and e.get("country") == "419")])
        decisions = catalogue.classify_names(
            ["Blackberry_1305_r1-11_ESusUNlatam_xt9_ALM3.ldb",
             "Blackberry_1305_r1-28_ESusUN_xt9_ALM3.ldb"], trimmed)
        latam = [d for d in decisions if "latam" in d.file_name][0]
        self.assertFalse(latam.overridden)
        self.assertEqual("es", latam.locale)
        self.assertEqual(catalogue.DUPLICATE, latam.status)

    def test_two_files_claiming_one_locale_are_still_settled_by_rank(self):
        # No such pair remains in the real catalogue, but `resolve` must keep
        # working: a second Afrikaans pack loses to the one the table names.
        names = ["Blackberry_1305_r1-2_AFlsUN_xt9_ALM3.ldb",
                 "Blackberry_1305_r9-9_AFlsUN_xt9_ALM3.ldb"]
        decisions = dict((d.file_name, d) for d in catalogue.classify_names(names, self.table))
        self.assertEqual(catalogue.PUBLISHED,
                         decisions["Blackberry_1305_r1-2_AFlsUN_xt9_ALM3.ldb"].status)
        loser = decisions["Blackberry_1305_r9-9_AFlsUN_xt9_ALM3.ldb"]
        self.assertEqual(catalogue.DUPLICATE, loser.status)
        self.assertIn("Blackberry_1305_r1-2_AFlsUN_xt9_ALM3.ldb", loser.reason)

    def test_a_locale_absent_from_the_engine_table_is_unsupported(self):
        # Exercised with a trimmed table rather than a fabricated filename: the
        # catalogue happens to contain no file whose language parses cleanly but
        # is missing from the table, and that must stay a handled case.
        trimmed = EngineTable([e for e in self.table.entries if e["language"] != "af"])
        decisions = catalogue.classify_names(["Blackberry_1305_r1-2_AFlsUN_xt9_ALM3.ldb"], trimmed)
        self.assertEqual(catalogue.UNSUPPORTED, decisions[0].status)
        self.assertIn("'af' is not in the engine's 104-entry locale table", decisions[0].reason)

    # -- the manifest item -------------------------------------------------

    def test_a_published_item_has_exactly_the_contracted_fields(self):
        decision = self.by_name["Blackberry_1305_r1-2_AFlsUN_xt9_ALM3.ldb"]
        decision.sha256 = "0" * 64
        decision.size = 4400000
        self.assertEqual({"locale": "af", "name": "Afrikaans",
                          "file": "Blackberry_1305_r1-2_AFlsUN_xt9_ALM3.ldb",
                          "sha256": "0" * 64, "size": 4400000},
                         decision.item())

    def test_a_variant_item_carries_its_group(self):
        decision = self.by_name["Blackberry_1305_r1-20_DEusUNCH_xt9_ALM3.ldb"]
        decision.sha256 = "1" * 64
        decision.size = 3843297
        self.assertEqual({"locale": "de_CH", "name": "German (Switzerland)",
                          "file": "Blackberry_1305_r1-20_DEusUNCH_xt9_ALM3.ldb",
                          "sha256": "1" * 64, "size": 3843297, "group": "de"},
                         decision.item())


class ScanTest(unittest.TestCase):

    def test_the_new_subfolder_is_scanned_and_manifest_json_ignored(self):
        import os
        import shutil
        import tempfile
        root = tempfile.mkdtemp(prefix="bbkb-scan-")
        try:
            os.makedirs(os.path.join(root, "new"))
            for name in ("Blackberry_1305_r1-2_AFlsUN_xt9_ALM3.ldb", "manifest.json", "notes.txt"):
                open(os.path.join(root, name), "w").close()
            open(os.path.join(root, "new", "Blackberry_1305_r1-20_DEusUNCH_xt9_ALM3.ldb"), "w").close()
            found = catalogue.scan(root)
            # Both folders in one list, ordered by filename ('0' sorts before '_').
            self.assertEqual(["Blackberry_1305_r1-20_DEusUNCH_xt9_ALM3.ldb",
                              "Blackberry_1305_r1-2_AFlsUN_xt9_ALM3.ldb"],
                             [name for name, _, _ in found])
            self.assertEqual(["new", ""], [source for _, _, source in found])
        finally:
            shutil.rmtree(root, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
