package dev.bbkb.ime.core.engine.dlm

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineLanguagesTest {
    @Test
    fun theLowByteNamesTheLanguage() {
        assertEquals("en", EngineLanguages.code(0x0b09))
        assertEquals("ru", EngineLanguages.code(0x0119))
        assertEquals("kk", EngineLanguages.code(0x013f))
        assertEquals("en", EngineLanguages.code(0x0809))
    }

    @Test
    fun noLanguageAndUnknownIdsHaveNoName() {
        assertNull(EngineLanguages.code(EngineLanguages.ANY))
        assertNull(EngineLanguages.code(0x0000))
        assertNull(EngineLanguages.displayName(0x00ff, Locale.ENGLISH))
    }

    @Test
    fun displayNamesFollowThePhonesLanguage() {
        assertEquals("English", EngineLanguages.displayName(0x0b09, Locale.ENGLISH))
        assertEquals("Russian", EngineLanguages.displayName(0x0119, Locale.ENGLISH))
        assertEquals("Englisch", EngineLanguages.displayName(0x0b09, Locale.GERMAN))
    }
}
