package dev.bbkb.ime.core.locale

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The engine's {@code setInputMethod} wants the VARIANT, not the locale string. With
 * "zh_TW_stroke" it stayed in Pinyin and stroke and Zhuyin keyboards produced no candidates on
 * either Chinese pack (KEY2, 2026-09-28). The Cangjie branch reads a setting and is covered on
 * the device.
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
