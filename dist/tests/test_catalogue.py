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
        self.assertEqual(105, tally[catalogue.PUBLISHED])
        self.assertEqual(7, tally[catalogue.REFUSED])
        self.assertEqual(4, tally[catalogue.DUPLICATE])
        self.assertEqual(0, tally[catalogue.UNSUPPORTED])
        self.assertEqual(116, len(self.decisions))

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

    def test_chinese_market_english_is_skipped_and_names_the_winner(self):
        decision = self.by_name["Blackberry_1305_r1-3_ENubUNZH_xt9_2.ldb"]
        self.assertEqual(catalogue.DUPLICATE, decision.status)
        self.assertEqual("en", decision.locale)
        self.assertIn("Blackberry_1305_r1-76_ENubUN_xt9_ALM3.ldb", decision.reason)
        self.assertIn("'ZH'", decision.reason)
        self.assertIn("en_ZH", decision.reason)
        self.assertIsNone(decision.name)

    def test_latam_spanish_is_skipped_rather_than_overwriting_plain_spanish(self):
        decision = self.by_name["Blackberry_1305_r1-11_ESusUNlatam_xt9_ALM3.ldb"]
        self.assertEqual(catalogue.DUPLICATE, decision.status)
        self.assertIn("Blackberry_1305_r1-28_ESusUN_xt9_ALM3.ldb", decision.reason)
        self.assertIn("es_419", decision.reason)

    def test_the_zh_winner_is_the_file_the_engine_table_calls_zh(self):
        # Not the alphabetically-first one: the Big5 packs sort earlier but the
        # table registers them as zh_TW / zh_HK.
        winner = self.by_name["Blackberry_1305_r1-17-10-3_ZHsbUNps_GB2312_xt9_big_ALM.ldb"]
        self.assertTrue(winner.published)
        self.assertEqual("zh", winner.locale)
        for loser in ("Blackberry_1305_r1-15-2-6_ZHtbUNps_Big5HKSCS_bpmf_pinyin_CJ_xt9_bigTW_ALM.ldb",
                      "Blackberry_1305_r1-22-2-5_ZHtbUNps_Big5HKSCS_bpmf_pinyin_CJ_xt9_big_ALM.ldb"):
            self.assertEqual(catalogue.DUPLICATE, self.by_name[loser].status)
            self.assertIn(winner.file_name, self.by_name[loser].reason)

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
