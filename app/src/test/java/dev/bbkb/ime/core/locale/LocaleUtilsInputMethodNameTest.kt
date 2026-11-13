package dev.bbkb.ime.core.locale

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The engine's `setInputMethod` wants the variant ("stroke"), not the locale string
 * ("zh_TW_stroke", which leaves it in Pinyin). The Cangjie branch reads a setting; not covered here.
 */
class LocaleUtilsInputMethodNameTest {

    @Test
    fun chineseSubtypesHandTheEngineTheirVariant() {
        assertEquals("stroke", LocaleUtils.toNuanceInputMethodName(Locale("zh", "TW", "stroke")))
        assertEquals("stroke", LocaleUtils.toNuanceInputMethodName(Locale("zh", "CN", "stroke")))
        assertEquals("zhuyin", LocaleUtils.toNuanceInputMethodName(Locale("zh", "TW", "zhuyin")))
        assertEquals("pinyin", LocaleUtils.toNuanceInputMethodName(Locale("zh", "CN", "pinyin")))
        assertEquals("pinyin", LocaleUtils.toNuanceInputMethodName(Locale("zh", "TW", "pinyin")))
    }

    @Test
    fun theLayoutSyncStillGetsTheFullLocaleString() {
        assertEquals("zh_TW_stroke", LocaleUtils.toNuanceLocaleString(Locale("zh", "TW", "stroke")))
        assertEquals("en_US", LocaleUtils.toNuanceLocaleString(Locale("en", "US")))
    }
}
