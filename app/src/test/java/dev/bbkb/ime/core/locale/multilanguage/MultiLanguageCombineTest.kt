package dev.bbkb.ime.core.locale.multilanguage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which keyboard languages may share one keyboard as extra prediction languages: the same script.
 * Until 2026-09-28 only ASCII-capable subtypes were candidates, so Russian could not add
 * Ukrainian and Hindi could not add Marathi although they share a layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MultiLanguageCombineTest {

    @Test
    fun latinKeyboardsCombineWithEachOtherIncludingVietnamese() {
        assertTrue(MultiLanguageRepository.canCombine("en_US", "de"))
        assertTrue(MultiLanguageRepository.canCombine("en_US", "vi"))
        assertTrue(MultiLanguageRepository.canCombine("vi", "fr_CA"))
        assertTrue(MultiLanguageRepository.isLatinScript("en_US"))
        assertTrue(MultiLanguageRepository.isLatinScript("vi"))
    }

    @Test
    fun sameScriptNonLatinKeyboardsCombine() {
        assertTrue(MultiLanguageRepository.canCombine("ru", "uk"))
        assertTrue(MultiLanguageRepository.canCombine("uk", "be"))
        assertTrue(MultiLanguageRepository.canCombine("hi", "mr"))
        assertTrue(MultiLanguageRepository.canCombine("ar", "fa"))
        assertFalse(MultiLanguageRepository.isLatinScript("ru"))
    }

    @Test
    fun differentScriptsDoNot() {
        assertFalse(MultiLanguageRepository.canCombine("ru", "en_US"))
        assertFalse(MultiLanguageRepository.canCombine("hi", "bn"))
        assertFalse(MultiLanguageRepository.canCombine("el", "ru"))
        assertFalse(MultiLanguageRepository.canCombine("iw", "ar"))
    }

    @Test
    fun cjkAndUnknownNeverCombine() {
        assertFalse(MultiLanguageRepository.canCombine("ko", "ja"))
        assertFalse(MultiLanguageRepository.canCombine("zh_CN_pinyin", "zh_TW_zhuyin"))
        assertFalse(MultiLanguageRepository.canCombine("ja", "ja"))
        assertFalse(MultiLanguageRepository.canCombine("zz", "en_US"))
        assertFalse(MultiLanguageRepository.canCombine("", "en_US"))
        assertFalse(MultiLanguageRepository.canCombine("tlh", "en_US"))
    }
}
