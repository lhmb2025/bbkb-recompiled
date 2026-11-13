package dev.bbkb.ime.core.settings.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The name a voice language is listed under: the language's own name for itself. */
class VoiceLanguageDisplayNameTest {

    @Test
    fun aLanguageAndCountryCode_isNamedInItsOwnLanguage() {
        assertEquals("Français (France)", voiceLanguageDisplayName("fr-FR"))
    }

    @Test
    fun anUnderscoreCode_readsLikeAHyphenOne() {
        assertEquals(voiceLanguageDisplayName("en-US"), voiceLanguageDisplayName("en_US"))
    }

    @Test
    fun aCodeWithAScript_namesTheScriptAndTheCountry() {
        // Split by hand, "Hant" would be taken for the country and shown as "(HANT)".
        val name = voiceLanguageDisplayName("zh-Hant-TW")
        assertFalse(name, name.contains("HANT", ignoreCase = true))
        assertTrue(name, name.contains("繁體"))
        assertTrue(name, name.contains("台灣"))
    }

    @Test
    fun aCodeThatIsNotALanguage_isShownAsItIs() {
        assertEquals("x", voiceLanguageDisplayName("x"))
        assertEquals("", voiceLanguageDisplayName(""))
    }
}
