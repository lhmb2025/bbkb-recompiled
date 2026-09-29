package com.blackberry.nuanceshim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The engine's Amharic dictionary stores a syllable as its first-order letter plus a Latin
 * marker for orders two to seven (u i a e ə o). On the KEY2 (2026-09-28) it offered "ሰለመu" for
 * ሰለሙ and predicted "ነወə" for ነው, so the shim translates in both directions.
 */
class EthiopicSyllablesTest {

    @Test
    fun aWordDecomposesToBaseLettersAndMarkers() {
        assertEquals("ሰለaመə", EthiopicSyllables.decompose("ሰላም"))
        assertEquals("ነወə", EthiopicSyllables.decompose("ነው"))
        assertEquals("ወəሰəጠə", EthiopicSyllables.decompose("ውስጥ"))
        assertEquals("አiተəየoጰəየa", EthiopicSyllables.decompose("ኢትዮጵያ"))
    }

    @Test
    fun theEngineFormComposesBackToSyllables() {
        assertEquals("ሰላም", EthiopicSyllables.compose("ሰለaመə"))
        assertEquals("ነው", EthiopicSyllables.compose("ነወə"))
        assertEquals("ሰለሙ", EthiopicSyllables.compose("ሰለመu"))
        assertEquals("ላይ", EthiopicSyllables.compose("ለaየə"))
    }

    @Test
    fun everyRegularSyllableRoundTrips() {
        val all = StringBuilder()
        for (cp in 0x1200..0x135A) {
            if (Character.getType(cp) == Character.OTHER_LETTER.toInt()) all.appendCodePoint(cp)
        }
        val text = all.toString()
        assertEquals(text, EthiopicSyllables.compose(EthiopicSyllables.decompose(text)))
    }

    @Test
    fun baseLettersEighthFormsAndOtherTextPassThrough() {
        assertEquals("ሰ", EthiopicSyllables.decompose("ሰ"))
        assertEquals("ሟ", EthiopicSyllables.decompose("ሟ"))
        assertEquals("hello ሰ 123", EthiopicSyllables.decompose("hello ሰ 123"))
        assertEquals("hello", EthiopicSyllables.compose("hello"))
        assertEquals("ሰ፡ሰ", EthiopicSyllables.compose("ሰ፡ሰ"))
        assertEquals("", EthiopicSyllables.decompose(""))
        assertEquals(null, EthiopicSyllables.compose(null))
    }

    @Test
    fun aMarkerWithNoSyllableInThatSlotIsLeftAlone() {
        // ቈ (qwä) has no second order: U+1249 is unassigned.
        assertEquals("ቈu", EthiopicSyllables.compose("ቈu"))
        assertEquals("ቋ", EthiopicSyllables.compose("ቈa"))
        // A marker after a non-base syllable stays a letter of its own.
        assertEquals("ሟo", EthiopicSyllables.compose("ሟo"))
        assertEquals("ሰx", EthiopicSyllables.compose("ሰx"))
    }

    @Test
    fun onlyEthiopicLanguagesNeedTheTranslation() {
        assertTrue(EthiopicSyllables.appliesTo(Locale("am")))
        assertTrue(EthiopicSyllables.appliesTo(Locale("ti", "ET")))
        assertFalse(EthiopicSyllables.appliesTo(Locale("en", "US")))
        assertFalse(EthiopicSyllables.appliesTo(Locale("bo")))
        assertFalse(EthiopicSyllables.appliesTo(null))
    }
}
