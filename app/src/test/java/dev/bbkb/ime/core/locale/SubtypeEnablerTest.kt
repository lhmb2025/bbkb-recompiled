package dev.bbkb.ime.core.locale

import android.view.inputmethod.InputMethodSubtype
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which of this keyboard's languages a pack's locale turns on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SubtypeEnablerTest {

    private fun subtype(locale: String, extra: String = "EmojiCapable") =
        InputMethodSubtype.InputMethodSubtypeBuilder()
            .setSubtypeLocale(locale)
            .setSubtypeMode("keyboard")
            .setSubtypeExtraValue(extra)
            .build()

    private val multiLanguageKorean =
        subtype("ko_KR", "KeyboardLayoutSet=null,AsciiCapable,isAdditionalSubtype,AdditionalLocales=en_US")

    @Test
    fun prefersTheExactLocale() {
        val all = listOf(subtype("en_US"), subtype("en_GB"))
        assertEquals("en_GB", SubtypeEnabler.pickSubtypeFor("en_GB", all)?.locale)
    }

    @Test
    fun aRegionalPackPicksThatRegionsKeyboard() {
        val all = listOf(subtype("zh_CN_pinyin"), subtype("zh_HK_cangjie"), subtype("zh_TW_zhuyin"))
        assertEquals("zh_HK_cangjie", SubtypeEnabler.pickSubtypeFor("zh_HK", all)?.locale)
    }

    @Test
    fun aLanguageOnlyPackPicksAnyKeyboardForTheLanguage() {
        val all = listOf(subtype("en_US"), subtype("bn_IN"))
        assertEquals("bn_IN", SubtypeEnabler.pickSubtypeFor("bn", all)?.locale)
    }

    @Test
    fun neverPicksAMultiLanguageKeyboard() {
        // The keyboard the beta reporter ended up with: "Korean" in name, English in practice.
        val all = listOf(multiLanguageKorean, subtype("ko"))
        assertEquals("ko", SubtypeEnabler.pickSubtypeFor("ko", all)?.locale)
        assertNull(SubtypeEnabler.pickSubtypeFor("ko", listOf(multiLanguageKorean)))
    }

    @Test
    fun noKeyboardForTheLanguageIsNull() {
        assertNull(SubtypeEnabler.pickSubtypeFor("am", listOf(subtype("en_US"), subtype("zz"))))
    }
}
