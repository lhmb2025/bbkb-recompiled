package dev.bbkb.ime.core.locale.multilanguage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wizard's starting primary language, and dropping keyboards saved without a layout: the two
 * halves of the "Korean + English only types English" beta report.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MultiLanguageDefaultsTest {

    private val available = listOf(
        LocaleItem("de", "German"),
        LocaleItem("en_GB", "English (UK)"),
        LocaleItem("en_US", "English (US)"),
    )

    @Test
    fun startsOnTheSystemLanguageWhenItIsListed() {
        assertEquals("en_GB", MultiLanguageRepository.pickDefaultPrimaryLocale(available, "en_GB")?.first)
    }

    @Test
    fun fallsBackToAKeyboardForTheSameLanguage() {
        assertEquals("en_GB", MultiLanguageRepository.pickDefaultPrimaryLocale(available, "en_IE")?.first)
    }

    @Test
    fun aNonLatinSystemLanguageStartsOnTheFirstListedLanguage() {
        // Korean cannot lead a multi-language keyboard, so it must not be pre-selected.
        assertEquals("de", MultiLanguageRepository.pickDefaultPrimaryLocale(available, "ko_KR")?.first)
    }

    @Test
    fun nothingAvailableIsNull() {
        assertNull(MultiLanguageRepository.pickDefaultPrimaryLocale(emptyList(), "ko_KR"))
    }

    @Test
    fun aKeyboardSavedWithoutALayoutIsDropped() {
        assertNull(MultiLanguageUtils.parseConfig("ko_KR/en_US:null"))
        assertEquals("en_US/fr_FR:qwerty",
            MultiLanguageUtils.serializeConfig(MultiLanguageUtils.parseConfig("en_US/fr_FR:qwerty")))
    }
}
