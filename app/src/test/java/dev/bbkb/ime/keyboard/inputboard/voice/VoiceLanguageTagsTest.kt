package dev.bbkb.ime.keyboard.inputboard.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * The tag rules, pinned to what Google's recognition service was observed to do on the KEY2
 * (2026-09-28): underscore forms are invalid and silently replaced by the phone default, bare
 * codes work (with iw/in mapped), hyphenated tags pass through.
 */
class VoiceLanguageTagsTest {

    @Test
    fun keyboardLocalesBecomeHyphenatedTags() {
        val expected = mapOf(
            "en_US" to "en-US",
            "en_GB" to "en-GB",
            "de_CH" to "de-CH",
            "fr_CA" to "fr-CA",
            "hy_AM" to "hy-AM",
            "es_419" to "es-419",
            "de" to "de",
            "af" to "af",
            "fil" to "fil",
        )
        for ((raw, tag) in expected) {
            assertEquals(raw, tag, VoiceLanguageTags.normalise(raw))
        }
    }

    @Test
    fun layoutSuffixesAreNotPartOfTheTag() {
        assertEquals("zh-CN", VoiceLanguageTags.normalise("zh_CN_pinyin"))
        assertEquals("zh-CN", VoiceLanguageTags.normalise("zh_CN_stroke"))
        assertEquals("zh-TW", VoiceLanguageTags.normalise("zh_TW_zhuyin"))
        assertEquals("zh-TW", VoiceLanguageTags.normalise("zh_TW_stroke"))
        assertEquals("zh-HK", VoiceLanguageTags.normalise("zh_HK_cangjie"))
    }

    @Test
    fun deprecatedIsoCodesAreMappedToTheirCurrentOnes() {
        assertEquals("he", VoiceLanguageTags.normalise("iw"))
        assertEquals("he-IL", VoiceLanguageTags.normalise("iw_IL"))
        assertEquals("id", VoiceLanguageTags.normalise("in"))
        assertEquals("id-ID", VoiceLanguageTags.normalise("in_ID"))
        assertEquals("yi", VoiceLanguageTags.normalise("ji"))
        assertEquals("jv", VoiceLanguageTags.normalise("jw"))
    }

    @Test
    fun hyphenatedTagsPassThroughUntouched() {
        assertEquals("en-US", VoiceLanguageTags.normalise("en-US"))
        assertEquals("cmn-Hans-CN", VoiceLanguageTags.normalise("cmn-Hans-CN"))
        assertEquals("yue-Hant-HK", VoiceLanguageTags.normalise("yue-Hant-HK"))
    }

    @Test
    fun nothingUsableIsNull() {
        assertNull(VoiceLanguageTags.normalise(null))
        assertNull(VoiceLanguageTags.normalise(""))
        assertNull(VoiceLanguageTags.normalise("   "))
        assertNull(VoiceLanguageTags.normalise("zz"))
        assertNull(VoiceLanguageTags.normalise("zz-ZZ"))
        assertNull(VoiceLanguageTags.normalise("_US"))
        assertNull(VoiceLanguageTags.normalise("english"))
    }

    @Test
    fun keyboardLanguageWinsWhenTheSettingSaysSo() {
        assertEquals("de-CH", VoiceLanguageTags.effectiveTag(true, "de_CH", "en-US"))
        assertEquals("he", VoiceLanguageTags.effectiveTag(true, "iw", "en-US"))
    }

    @Test
    fun manualLanguageIsUsedOtherwiseAndForTheNoLanguageKeyboard() {
        assertEquals("fr-FR", VoiceLanguageTags.effectiveTag(false, "de_CH", "fr-FR"))
        assertEquals("fr-FR", VoiceLanguageTags.effectiveTag(true, "zz", "fr-FR"))
        assertEquals("fr-FR", VoiceLanguageTags.effectiveTag(true, null, "fr_FR"))
    }

    @Test
    fun theDefaultVoiceLanguageIsTheLastResort() {
        assertEquals("en-US", VoiceLanguageTags.effectiveTag(false, "de_CH", null))
        assertEquals("en-US", VoiceLanguageTags.effectiveTag(true, "zz", ""))
    }

    @Test
    fun allowedTagsListThePrimaryThenTheExtrasWithoutDuplicates() {
        val extras = linkedSetOf(Locale("fr", "CA"), Locale("de"), Locale("en", "US"), Locale("zz"))
        assertEquals(listOf("en-US", "fr-CA", "de"), VoiceLanguageTags.allowedTags("en-US", extras))
        assertEquals(listOf("en-US"), VoiceLanguageTags.allowedTags("en-US", null))
        assertEquals(listOf("en-US"), VoiceLanguageTags.allowedTags("en-US", emptySet()))
    }

    @Test
    fun displayNameFallsBackToTheTag() {
        assertEquals("", VoiceLanguageTags.displayName(null))
        assertEquals("", VoiceLanguageTags.displayName(""))
        // The JVM names this one; the exact wording is the platform's, so only check it is a name.
        val english = VoiceLanguageTags.displayName("en-US")
        assertEquals(true, english.startsWith("English"))
    }
}
