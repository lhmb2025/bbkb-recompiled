package com.blackberry.nuanceshim.languagepack

import dev.bbkb.ime.core.settings.screens.extractCountryFromFileName
import dev.bbkb.ime.core.settings.screens.extractLanguageFromFileName
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The side-load filename parser over the BlackBerry LDB catalogue (116 files): 106 yield a
 * loadable language, all 11 country-qualified packs yield `language_COUNTRY`, and 10 are refused
 * (`brx`, `doi`, `mai`, `mni`, `sat`, `sd`, `hl` are absent from the engine's 105-entry locale table).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LdbFilenameParsingTest {

    private fun parse(fileName: String): String {
        val lang = extractLanguageFromFileName(fileName)
        if (lang.isEmpty()) return "REJECTED"
        val country = extractCountryFromFileName(fileName, lang)
        return if (country != null) "${lang}_$country" else lang
    }

    @Test
    fun everyCountryQualifiedPackInTheCatalogueParses() {
        assertEquals("en_US", parse("Blackberry_1305_r1-5_ENubUNUS_xt9_ALM3.ldb"))
        assertEquals("en_UK", parse("Blackberry_1305_r1-17_ENubUNUK_xt9_ALM3.ldb"))
        assertEquals("en_AU", parse("Blackberry_1305_r1-9_ENubUNAU_xt9_ALM3.ldb"))
        assertEquals("es_ES", parse("Blackberry_1305_r1-12_ESusUNES_xt9_ALM3.ldb"))
        assertEquals("pt_BR", parse("Blackberry_1305_r1-2_PTusUNBR_xt9_ALM3.ldb"))
        assertEquals("pt_PT", parse("Blackberry_1305_r1-2_PTusUNPT_xt9_ALM3.ldb"))
        assertEquals("fr_CA", parse("Blackberry_1305_r1-6_FRusUNCA_xt9_ALM3.ldb"))
        assertEquals("fr_CH", parse("Blackberry_1305_r1-21_FRusUNCH_xt9_ALM3.ldb"))
        assertEquals("de_CH", parse("Blackberry_1305_r1-20_DEusUNCH_xt9_ALM3.ldb"))
        assertEquals("it_CH", parse("Blackberry_1305_r1-18_ITusUNCH_xt9_ALM3.ldb"))
        assertEquals("nl_BE", parse("Blackberry_1305_r1-6_NLusUNBE_xt9_ALM3.ldb"))
    }

    @Test
    fun scriptVariantsInstallAsTheBaseLanguageRatherThanInventingARegion() {
        // The suffix names a script, not a region; as a country it would make a directory nothing reads.
        assertEquals("tt", parse("Blackberry_1305_r1-1_TTlsUNcyrillic_xt9_ALM3.ldb"))
        assertEquals("ks", parse("Blackberry_1305_r1-2_KSlsUNdevanagari_xt9_ALM3.ldb"))
        assertEquals("si", parse("Blackberry_1305_r1-2_SIlsUNAlternate_xt9_ALM3.ldb"))
        assertEquals("ja", parse("Blackberry_JAlsUNkana_conv_xt9_ALM3.ldb"))
        assertEquals("zh", parse("Blackberry_1305_r1-17-10-3_ZHsbUNps_GB2312_xt9_big_ALM.ldb"))
    }

    @Test
    fun threeLetterCodesAreRejectedRatherThanTruncatedIntoAnotherLanguage() {
        // Truncating would overwrite real packs (SAT -> "sa", MNI -> "mn"); none of the three-letter
        // languages is in the engine's locale table, so a visible refusal is right.
        assertEquals("REJECTED", parse("Blackberry_1305_r1-2_SATlsUNdevanagari_xt9.ldb"))
        assertEquals("REJECTED", parse("Blackberry_1305_r1-2_MNIlsUN_xt9.ldb"))
        assertEquals("REJECTED", parse("Blackberry_1305_r1-2_DOIlsUN_xt9.ldb"))
    }

    @Test
    fun theBaseLanguagesTheThreeLetterPacksUsedToOverwriteStillParse() {
        // The guard keys off the uppercase language block, so real Sanskrit and Mongolian still parse.
        assertEquals("sa", parse("Blackberry_1305_r1-2_SAlsUN_xt9_ALM3.ldb"))
        assertEquals("mn", parse("Blackberry_1305_r1-2_MNlsUN_xt9_ALM3.ldb"))
    }

    @Test
    fun filenameSpellingsAreTranslatedToTheSpellingTheStackUses() {
        // LDB names carry retired or less-specific codes; the engine table, manifest and method.xml
        // use the expected ones.
        assertEquals("jv", parse("Blackberry_1305_r1-2_JWlsUN_xt9_ALM3.ldb"))   // Javanese
        assertEquals("iw", parse("Blackberry_1305_r1-6_HElsUN_xt9_ALM3.ldb"))   // Hebrew
        assertEquals("in", parse("Blackberry_1305_r1-6_IDlbUN_xt9_ALM3.ldb"))   // Indonesian
        assertEquals("fil", parse("Blackberry_1305_r1-7_TLlsUN_xt9_ALM3.ldb"))  // Filipino
        assertEquals("nb", parse("Blackberry_1305_r1-6_NOusUN_xt9_ALM3.ldb"))   // Norwegian
    }

    @Test
    fun theOtherThreeLetterAndUnknownCodesAreRejected() {
        assertEquals("REJECTED", parse("Blackberry_1305_r1-2_BRXlsUN_xt9.ldb"))   // Bodo
        assertEquals("REJECTED", parse("Blackberry_1305_r1-2_MAIlsUN_xt9.ldb"))   // Maithili
        assertEquals("REJECTED", parse("Blackberry_1305_r1-2_HLlbUN_xt9_ALM3.ldb")) // "hl": not ISO
        // `sd` is real ISO but absent from the engine's locale table: do not add it to the allowlist.
        assertEquals("REJECTED", parse("Blackberry_1305_r1-2_SDlsUNdevanagari_xt9.ldb"))
    }

    /** Known, accepted collision: each lands on its own base language, which has no separate engine entry. */
    @Test
    fun latamSpanishAndChineseMarketEnglishCollideWithTheirBaseLanguage() {
        // es-419's region is in the asset path, not the filename ("latam"), so it lands on plain Spanish.
        assertEquals("es", parse("Blackberry_1305_r1-11_ESusUNlatam_xt9_ALM3.ldb"))
        // "ZH" is a market (English for Chinese users), not a country: parses as plain English.
        assertEquals("en", parse("Blackberry_1305_r1-3_ENubUNZH_xt9_2.ldb"))
    }
}
