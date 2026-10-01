package dev.bbkb.ime.core.locale

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Indonesian, Hebrew and Yiddish are spelled `in`, `iw` and `ji` everywhere in this app (the
 * subtypes, both pack catalogs, the installed-pack directories, the script tables and the engine's
 * language table). Java 17 and Android 15 (apps targeting API 35) stopped converting `Locale` to
 * those codes, so on an Android 15 phone an Indonesian keyboard found no dictionary and the engine
 * was handed a language it does not know. [LocaleUtils.languageCode] is the bridge; this test runs
 * on a modern JDK, so it sees exactly what the phone sees.
 */
class LocaleUtilsLanguageCodeTest {

    @Test
    fun theRetiredCodesComeBackWhicheverSpellingBuiltTheLocale() {
        assertEquals("in", LocaleUtils.languageCode(Locale("in")))
        assertEquals("in", LocaleUtils.languageCode(Locale("id")))
        assertEquals("iw", LocaleUtils.languageCode(Locale("iw")))
        assertEquals("iw", LocaleUtils.languageCode(Locale("he")))
        assertEquals("ji", LocaleUtils.languageCode(Locale("ji")))
        assertEquals("ji", LocaleUtils.languageCode(Locale("yi")))
    }

    @Test
    fun everyOtherLanguageIsUntouched() {
        assertEquals("en", LocaleUtils.languageCode(Locale("en", "US")))
        assertEquals("ar", LocaleUtils.languageCode(Locale("ar")))
        assertEquals("fil", LocaleUtils.languageCode(Locale("fil")))
        assertEquals("", LocaleUtils.languageCode(Locale("")))
    }

    @Test
    fun theSubtypeStringRoundTripsThroughTheAppSpelling() {
        assertEquals("in", LocaleUtils.localeString(LocaleUtils.constructLocaleFromString("in")))
        assertEquals("in_ID", LocaleUtils.localeString(Locale("in", "ID")))
        assertEquals("iw_IL", LocaleUtils.localeString(Locale("he", "IL")))
        assertEquals("zh_TW_stroke", LocaleUtils.localeString(Locale("zh", "TW", "stroke")))
        assertEquals("en_US", LocaleUtils.localeString(Locale("en", "US")))
        assertEquals("de", LocaleUtils.localeString(Locale("de")))
    }

    @Test
    fun theEngineAndTextTablesSeeTheOldCodes() {
        assertEquals("iw", LocaleUtils.toNuanceLocaleString(Locale("he")))
        assertEquals("in", LocaleUtils.toNuanceLocaleString(LocaleUtils.constructLocaleFromString("in")))
    }
}
