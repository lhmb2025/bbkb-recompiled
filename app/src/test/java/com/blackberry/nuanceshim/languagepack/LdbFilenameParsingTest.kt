package com.blackberry.nuanceshim.languagepack

import dev.bbkb.ime.core.settings.screens.extractCountryFromFileName
import dev.bbkb.ime.core.settings.screens.extractLanguageFromFileName
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the side-load filename parser makes of the full BlackBerry LDB catalogue (116 files,
 * 2026-09-16). Established by running the real parser over the whole list.
 *
 * After the 2026-09-16 fixes:
 *  - 106 of 116 yield a language the engine can load; 10 are refused.
 *  - all 11 country-qualified packs yield the right `language_COUNTRY`.
 *  - nothing is silently misidentified any more — the two that were are now explicit refusals.
 *
 * The refusals are all CORRECT: seven languages (`brx`, `doi`, `mai`, `mni`, `sat`, `sd`, `hl`)
 * are absent from the engine's 105-entry locale table and cannot load on this build at all.
 *
 * This file covers the FILENAME gate only. Whether an installed pack is SELECTABLE is a separate
 * question — `method.xml` has no subtype for 28 engine-supported languages. See
 * `docs/2026-09_language-pack-sideload_findings.md`.
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

    // ── the happy path: language-only and country-qualified ─────────────────────

    @Test
    fun everyCountryQualifiedPackInTheCatalogueParses() {
        // All 11 of them. Before 2026-09-16 every one of these produced a bare language code and
        // landed in the base language's directory.
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
        // The suffix here names a SCRIPT, not a region. Treating "devanagari" or "cyrillic" as a
        // country would produce a directory no installer ever looks in.
        assertEquals("tt", parse("Blackberry_1305_r1-1_TTlsUNcyrillic_xt9_ALM3.ldb"))
        assertEquals("ks", parse("Blackberry_1305_r1-2_KSlsUNdevanagari_xt9_ALM3.ldb"))
        assertEquals("si", parse("Blackberry_1305_r1-2_SIlsUNAlternate_xt9_ALM3.ldb"))
        assertEquals("ja", parse("Blackberry_JAlsUNkana_conv_xt9_ALM3.ldb"))
        assertEquals("zh", parse("Blackberry_1305_r1-17-10-3_ZHsbUNps_GB2312_xt9_big_ALM.ldb"))
    }

    // ── three-letter codes: refused, not truncated (fixed 2026-09-16) ──────────

    @Test
    fun threeLetterCodesAreRejectedRatherThanTruncatedIntoAnotherLanguage() {
        // FIXED 2026-09-16. These used to truncate: SAT -> "sa" (Sanskrit) and MNI -> "mn"
        // (Mongolian), both real languages with their own packs in this same catalogue, so
        // installing Manipuri silently overwrote Mongolian. None of the five three-letter
        // languages exists in the engine's locale table, so refusing them is the correct
        // outcome - the point of the fix is that it is now a refusal the user can see rather
        // than a corruption they cannot.
        assertEquals("REJECTED", parse("Blackberry_1305_r1-2_SATlsUNdevanagari_xt9.ldb"))
        assertEquals("REJECTED", parse("Blackberry_1305_r1-2_MNIlsUN_xt9.ldb"))
        assertEquals("REJECTED", parse("Blackberry_1305_r1-2_DOIlsUN_xt9.ldb"))
    }

    @Test
    fun theBaseLanguagesTheThreeLetterPacksUsedToOverwriteStillParse() {
        // The guard keys off the filename's uppercase language block, so the real Sanskrit and
        // Mongolian packs must be unaffected by it.
        assertEquals("sa", parse("Blackberry_1305_r1-2_SAlsUN_xt9_ALM3.ldb"))
        assertEquals("mn", parse("Blackberry_1305_r1-2_MNlsUN_xt9_ALM3.ldb"))
    }

    @Test
    fun filenameSpellingsAreTranslatedToTheSpellingTheStackUses() {
        // The LDB names carry retired or less-specific codes; the engine table, the manifest and
        // method.xml all use the right-hand column. Before the alias map these five installed
        // under a locale nothing could select, and the cleanup deleted them within the hour.
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
        // Sindhi IS two letters, and a real ISO code — but `sd` is absent from the engine's locale
        // table too, so rejecting it is right. Do NOT "fix" this by adding sd to the allowlist.
        assertEquals("REJECTED", parse("Blackberry_1305_r1-2_SDlsUNdevanagari_xt9.ldb"))
    }

    // ── [CHARACTERISED BUG] two packs still collapse onto a shipped language ───
    //
    // Left as-is deliberately: unlike the three-letter cases these are not silent corruption of a
    // DIFFERENT language's pack — each lands on its own base language, which is arguably the right
    // place for it given the engine has no separate entry (es-419's region lives in the asset path,
    // and "English for the Chinese market" has no locale of its own at all).

    @Test
    fun latamSpanishAndChineseMarketEnglishCollideWithTheirBaseLanguage() {
        // es-419 exists in the SHIPPED manifest, but its region lives in the asset PATH
        // (es_419/...), not the filename — the filename says "latam", lower-case, so the parser
        // reads no region and the pack lands on plain Spanish.
        assertEquals("es", parse("Blackberry_1305_r1-11_ESusUNlatam_xt9_ALM3.ldb"))
        // "ZH" here is a market, not a country: English for Chinese users. It parses as plain
        // English and would overwrite a side-loaded English pack.
        assertEquals("en", parse("Blackberry_1305_r1-3_ENubUNZH_xt9_2.ldb"))
    }
}
