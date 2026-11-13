package dev.bbkb.ime.core.locale

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The app spells Indonesian, Hebrew and Yiddish `in`, `iw`, `ji` everywhere (subtypes, pack
 * catalogs and directories, script and engine tables), but Java 17 and targetSdk 35+ `Locale` give
 * `id`, `he`, `yi`. [LocaleUtils.languageCode] bridges them; a modern JDK sees what the phone sees.
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
