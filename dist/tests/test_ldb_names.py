"""The Python port of the app's filename parser must agree with the app.

Two halves:

  * `AppTestParityTest` re-asserts, one for one, every expectation in
    `app/src/test/java/com/blackberry/nuanceshim/languagepack/LdbFilenameParsingTest.kt`.
    If the app's rules change, this fails and the publisher must be re-ported --
    that is the point. A pack published under a locale the app does not produce
    installs into a directory nothing looks in.
  * `CatalogueFixtureTest` runs the port over the whole real catalogue
    (`fixtures/catalogue.tsv`, 116 names) so no name can quietly change meaning.
"""

import unittest

from dist.lib import ldb_names
from dist.tests import support


def parse(file_name):
    """Exactly what the app's own test's `parse()` helper does."""
    return ldb_names.locale_of(file_name) or "REJECTED"


class AppTestParityTest(unittest.TestCase):

    def test_every_country_qualified_pack_in_the_catalogue_parses(self):
        self.assertEqual("en_US", parse("Blackberry_1305_r1-5_ENubUNUS_xt9_ALM3.ldb"))
        self.assertEqual("en_UK", parse("Blackberry_1305_r1-17_ENubUNUK_xt9_ALM3.ldb"))
        self.assertEqual("en_AU", parse("Blackberry_1305_r1-9_ENubUNAU_xt9_ALM3.ldb"))
        self.assertEqual("es_ES", parse("Blackberry_1305_r1-12_ESusUNES_xt9_ALM3.ldb"))
        self.assertEqual("pt_BR", parse("Blackberry_1305_r1-2_PTusUNBR_xt9_ALM3.ldb"))
        self.assertEqual("pt_PT", parse("Blackberry_1305_r1-2_PTusUNPT_xt9_ALM3.ldb"))
        self.assertEqual("fr_CA", parse("Blackberry_1305_r1-6_FRusUNCA_xt9_ALM3.ldb"))
        self.assertEqual("fr_CH", parse("Blackberry_1305_r1-21_FRusUNCH_xt9_ALM3.ldb"))
        self.assertEqual("de_CH", parse("Blackberry_1305_r1-20_DEusUNCH_xt9_ALM3.ldb"))
        self.assertEqual("it_CH", parse("Blackberry_1305_r1-18_ITusUNCH_xt9_ALM3.ldb"))
        self.assertEqual("nl_BE", parse("Blackberry_1305_r1-6_NLusUNBE_xt9_ALM3.ldb"))

    def test_script_variants_install_as_the_base_language(self):
        self.assertEqual("tt", parse("Blackberry_1305_r1-1_TTlsUNcyrillic_xt9_ALM3.ldb"))
        self.assertEqual("ks", parse("Blackberry_1305_r1-2_KSlsUNdevanagari_xt9_ALM3.ldb"))
        self.assertEqual("si", parse("Blackberry_1305_r1-2_SIlsUNAlternate_xt9_ALM3.ldb"))
        self.assertEqual("ja", parse("Blackberry_JAlsUNkana_conv_xt9_ALM3.ldb"))
        self.assertEqual("zh", parse("Blackberry_1305_r1-17-10-3_ZHsbUNps_GB2312_xt9_big_ALM.ldb"))

    def test_three_letter_codes_are_rejected_not_truncated(self):
        self.assertEqual("REJECTED", parse("Blackberry_1305_r1-2_SATlsUNdevanagari_xt9.ldb"))
        self.assertEqual("REJECTED", parse("Blackberry_1305_r1-2_MNIlsUN_xt9.ldb"))
        self.assertEqual("REJECTED", parse("Blackberry_1305_r1-2_DOIlsUN_xt9.ldb"))

    def test_the_base_languages_the_three_letter_packs_used_to_overwrite_still_parse(self):
        self.assertEqual("sa", parse("Blackberry_1305_r1-2_SAlsUN_xt9_ALM3.ldb"))
        self.assertEqual("mn", parse("Blackberry_1305_r1-2_MNlsUN_xt9_ALM3.ldb"))

    def test_filename_spellings_are_translated_to_the_stacks_spelling(self):
        self.assertEqual("jv", parse("Blackberry_1305_r1-2_JWlsUN_xt9_ALM3.ldb"))
        self.assertEqual("iw", parse("Blackberry_1305_r1-6_HElsUN_xt9_ALM3.ldb"))
        self.assertEqual("in", parse("Blackberry_1305_r1-6_IDlbUN_xt9_ALM3.ldb"))
        self.assertEqual("fil", parse("Blackberry_1305_r1-7_TLlsUN_xt9_ALM3.ldb"))
        self.assertEqual("nb", parse("Blackberry_1305_r1-6_NOusUN_xt9_ALM3.ldb"))

    def test_the_other_three_letter_and_unknown_codes_are_rejected(self):
        self.assertEqual("REJECTED", parse("Blackberry_1305_r1-2_BRXlsUN_xt9.ldb"))
        self.assertEqual("REJECTED", parse("Blackberry_1305_r1-2_MAIlsUN_xt9.ldb"))
        self.assertEqual("REJECTED", parse("Blackberry_1305_r1-2_HLlbUN_xt9_ALM3.ldb"))
        self.assertEqual("REJECTED", parse("Blackberry_1305_r1-2_SDlsUNdevanagari_xt9.ldb"))

    def test_latam_spanish_and_chinese_market_english_collide_with_their_base(self):
        # The app's characterised behaviour, and it stays characterised here:
        # the PARSER still reads these as plain es/en. The publisher does not
        # change that -- it names them after the engine table instead, which is
        # sound only because the download path installs by the manifest's
        # locale and never re-parses the filename (catalogue.TABLE_LOCALE_OVERRIDES).
        self.assertEqual("es", parse("Blackberry_1305_r1-11_ESusUNlatam_xt9_ALM3.ldb"))
        self.assertEqual("en", parse("Blackberry_1305_r1-3_ENubUNZH_xt9_2.ldb"))


class MarkerTest(unittest.TestCase):
    """`marker_after_un` is the publisher's own addition: what the app IGNORED."""

    def test_a_real_region_is_not_a_marker(self):
        self.assertIsNone(ldb_names.marker_after_un("Blackberry_1305_r1-5_ENubUNUS_xt9_ALM3.ldb"))
        self.assertIsNone(ldb_names.marker_after_un("Blackberry_1305_r1-20_DEusUNCH_xt9_ALM3.ldb"))

    def test_nothing_after_un_is_not_a_marker(self):
        self.assertIsNone(ldb_names.marker_after_un("Blackberry_1305_r1-76_ENubUN_xt9_ALM3.ldb"))

    def test_market_and_script_markers_are_reported(self):
        self.assertEqual("latam",
                         ldb_names.marker_after_un("Blackberry_1305_r1-11_ESusUNlatam_xt9_ALM3.ldb"))
        self.assertEqual("ZH", ldb_names.marker_after_un("Blackberry_1305_r1-3_ENubUNZH_xt9_2.ldb"))
        self.assertEqual("ps", ldb_names.marker_after_un(
            "Blackberry_1305_r1-17-10-3_ZHsbUNps_GB2312_xt9_big_ALM.ldb"))
        self.assertEqual("kana", ldb_names.marker_after_un("Blackberry_JAlsUNkana_conv_xt9_ALM3.ldb"))


class CatalogueFixtureTest(unittest.TestCase):

    def test_the_fixture_is_the_whole_catalogue(self):
        rows = support.catalogue_rows()
        self.assertEqual(116, len(rows))
        self.assertEqual(116, len(set(row[0] for row in rows)))

    def test_every_catalogue_name_parses_to_its_recorded_locale(self):
        for file_name, expected_locale, _ in support.catalogue_rows():
            self.assertEqual(expected_locale, parse(file_name), file_name)

    def test_exactly_seven_names_are_refused(self):
        refused = [row[0] for row in support.catalogue_rows() if row[1] == "REJECTED"]
        self.assertEqual(7, len(refused))
        blocks = sorted(ldb_names.language_block(name).lower() for name in refused)
        self.assertEqual(["brx", "doi", "hl", "mai", "mni", "sat", "sd"], blocks)


class EngineTableTest(unittest.TestCase):

    def setUp(self):
        self.table = ldb_names.EngineTable.load(support.REPO_ROOT)

    def test_the_table_has_the_engines_105_locales(self):
        self.assertEqual(105, len(self.table))
        self.assertIn("en_US", self.table)
        self.assertIn("fil", self.table)
        self.assertNotIn("de_CH", self.table)
        self.assertNotIn("sd", self.table)

    def test_names_come_from_the_table(self):
        self.assertEqual("English (United Kingdom)", self.table.name_of("en_UK"))
        self.assertEqual("German", self.table.name_of("de"))

    def test_a_file_can_be_traced_back_to_the_locale_the_table_registers_it_under(self):
        # The table names a payload for all 105 locales (10 preinstalled .ldb,
        # the rest downloadable .zip), so compare stems, not extensions. This
        # is what lets the duplicate tie-break prefer the engine's own file.
        self.assertEqual("es_419", self.table.locale_for_file(
            "Blackberry_1305_r1-11_ESusUNlatam_xt9_ALM3.ldb"))
        self.assertEqual("zh_TW", self.table.locale_for_file(
            "Blackberry_1305_r1-15-2-6_ZHtbUNps_Big5HKSCS_bpmf_pinyin_CJ_xt9_bigTW_ALM.ldb"))
        self.assertEqual("af", self.table.locale_for_file(
            "Blackberry_1305_r1-2_AFlsUN_xt9_ALM3.ldb"))
        # The Swiss/Belgian variants are in no engine entry at all.
        self.assertIsNone(self.table.locale_for_file(
            "Blackberry_1305_r1-20_DEusUNCH_xt9_ALM3.ldb"))
        self.assertIsNone(self.table.locale_for_file("not-a-catalogue-file.ldb"))

    def test_variant_names_are_built_from_the_base_languages_name(self):
        self.assertEqual("German (Switzerland)", ldb_names.variant_name(self.table, "de", "CH"))
        self.assertEqual("Dutch (Belgium)", ldb_names.variant_name(self.table, "nl", "BE"))


if __name__ == "__main__":
    unittest.main()
