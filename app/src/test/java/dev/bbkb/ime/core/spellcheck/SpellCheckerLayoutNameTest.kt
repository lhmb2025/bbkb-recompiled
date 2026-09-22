package dev.bbkb.ime.core.spellcheck

import dev.bbkb.ime.core.shared.ScriptUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import java.lang.reflect.InvocationTargetException
import java.util.Locale

/**
 * Characterises `AndroidSpellCheckerService.getKeyboardLayoutName`: script code -> keyboard layout
 * name used to build the spell checker's proximity keyboard, with a language sub-switch for
 * Cyrillic and a RuntimeException for every script it has no layout for.
 *
 * The script code is stubbed so every arm is reached independently of which languages the JVM's
 * Locale can express (e.g. "iw" is normalised to "he" on modern JDKs, so Hebrew is unreachable
 * through a real Locale here).
 */
class SpellCheckerLayoutNameTest {

    private val method = AndroidSpellCheckerService::class.java
        .getDeclaredMethod("getKeyboardLayoutName", Locale::class.java)
        .apply { isAccessible = true }

    private fun layoutFor(locale: Locale): String = try {
        method.invoke(null, locale) as String
    } catch (e: InvocationTargetException) {
        throw e.cause!!
    }

    /** Independent copy of the expected mapping; null = throws. */
    private fun expected(code: Int, language: String): String? = when (code) {
        0 -> "arabic"
        1 -> "armenian_phonetic"
        2 -> "bengali"
        3 -> when (language) {
            "bg" -> "bulgarian"
            "mn" -> "mongolian"
            "mk" -> "south_slavic"
            else -> "east_slavic"
        }
        4 -> "hindi"
        5 -> "georgian"
        6 -> "greek"
        8 -> "hangul"
        9 -> "hebrew"
        10 -> "romaji"
        11 -> "kannada"
        14 -> "qwerty"
        15 -> "malayalam"
        18 -> "tamil"
        19 -> "telugu"
        20 -> "thai"
        21 -> "qwerty"
        else -> null
    }

    private fun assertMapping(code: Int, locale: Locale) {
        val want = expected(code, locale.language)
        if (want != null) {
            assertEquals("script $code, $locale", want, layoutFor(locale))
            return
        }
        try {
            val got = layoutFor(locale)
            fail("script $code, $locale: expected RuntimeException, got $got")
        } catch (e: RuntimeException) {
            assertEquals(RuntimeException::class.java, e.javaClass)
            assertEquals("Unknown script supplied for [$locale] locale: $code", e.message)
        }
    }

    @Test
    fun everyScriptCodeMapsOrThrows() {
        val languages = listOf("xx", "bg", "mn", "mk", "ru", "uk", "sr", "", "BG")
        Mockito.mockStatic(ScriptUtils::class.java, Mockito.CALLS_REAL_METHODS).use { scripts ->
            for (code in -3..30) {
                scripts.`when`<Int> { ScriptUtils.getScriptFromLocale(ArgumentMatchers.any()) }.thenReturn(code)
                for (language in languages) {
                    assertMapping(code, Locale(language))
                    assertMapping(code, Locale(language, "ZZ"))
                }
            }
        }
    }

    @Test
    fun cyrillicSubSwitchIsByLanguageOnly() {
        Mockito.mockStatic(ScriptUtils::class.java, Mockito.CALLS_REAL_METHODS).use { scripts ->
            scripts.`when`<Int> { ScriptUtils.getScriptFromLocale(ArgumentMatchers.any()) }.thenReturn(3)
            assertEquals("bulgarian", layoutFor(Locale("bg", "RU")))
            assertEquals("mongolian", layoutFor(Locale("mn")))
            assertEquals("south_slavic", layoutFor(Locale("mk", "MK")))
            assertEquals("east_slavic", layoutFor(Locale("ru", "BG")))
            assertEquals("east_slavic", layoutFor(Locale("kk")))
        }
    }

    @Test
    fun realScriptTableSweep() {
        val languages = listOf(
            "af", "ar", "az", "be", "bg", "bn", "bs", "ca", "cs", "cy", "da", "de", "el", "en", "es",
            "et", "eu", "fa", "fi", "fil", "fr", "ga", "gl", "hi", "hr", "hu", "hy", "in", "is", "it",
            "iw", "he", "ja", "jv", "ka", "kk", "km", "kn", "ko", "ky", "lo", "lt", "lv", "mk", "ml",
            "mn", "mr", "ms", "my", "nb", "nl", "pl", "pt", "ro", "ru", "si", "sk", "sl", "sq", "sr",
            "su", "sv", "sw", "ta", "te", "th", "tr", "uk", "ur", "uz", "vi", "zh", "zz", "xx", ""
        )
        val seen = sortedSetOf<Int>()
        for (language in languages) {
            val locale = Locale(language, "US")
            val code = ScriptUtils.getScriptFromLocale(locale)
            seen += code
            assertMapping(code, locale)
        }
        assertEquals("sanity: sweep reached the codes the real table can produce", true, seen.containsAll(
            listOf(-1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21)
        ))
    }

    @Test
    fun spotChecks() {
        assertEquals("arabic", layoutFor(Locale("fa")))
        assertEquals("armenian_phonetic", layoutFor(Locale("hy")))
        assertEquals("east_slavic", layoutFor(Locale("ru", "RU")))
        assertEquals("bulgarian", layoutFor(Locale("bg")))
        assertEquals("hindi", layoutFor(Locale("mr")))
        assertEquals("hangul", layoutFor(Locale("ko", "KR")))
        assertEquals("romaji", layoutFor(Locale.JAPAN))
        assertEquals("qwerty", layoutFor(Locale.US))
        assertEquals("qwerty", layoutFor(Locale("vi")))
        assertEquals("thai", layoutFor(Locale("th")))
        try {
            layoutFor(Locale.CHINA)
            fail("zh has no spell-checker layout")
        } catch (e: RuntimeException) {
            assertEquals("Unknown script supplied for [zh_CN] locale: 7", e.message)
        }
    }
}
