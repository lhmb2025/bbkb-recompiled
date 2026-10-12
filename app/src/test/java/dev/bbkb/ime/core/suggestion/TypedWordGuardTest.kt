package dev.bbkb.ime.core.suggestion

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * [TypedWordGuard.allowsCorrection]: given a typed word the dictionary knows, which corrections
 * may still replace it. The frequency table is faked with the real LDB values for the words used
 * (kdb_freq_en.h, zipf*10), so the expectations here are what the shipped table produces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class TypedWordGuardTest {

    private val zipf = mapOf(
        "well" to 58, "we'll" to 54,
        "were" to 59, "we're" to 54,
        "its" to 59, "it's" to 59,
        "wed" to 31, "we'd" to 55,
        "can't" to 47, "won't" to 56,
        "ill" to 46, "i'll" to 43,
    )

    @Before
    fun fakeTable() = TypedWordGuard.setFrequencyForTest { zipf[it] ?: 0 }

    @After
    fun realTable() = TypedWordGuard.setFrequencyForTest(null)

    private fun allows(typed: String, correction: String, locale: Locale = Locale.US) =
        TypedWordGuard.allowsCorrection(typed, correction, locale)

    @Test
    fun differentLetters_areRefused() {
        // The four poisoning events: a learned neighbour outranking the exact typed word.
        assertFalse(allows("about", "bout"))
        assertFalse(allows("this", "his"))
        assertFalse(allows("doing", "Doping"))
        assertFalse(allows("hello", "jello"))
    }

    @Test
    fun caseOnly_isAllowed() {
        assertTrue(allows("paris", "Paris"))
        assertTrue(allows("i", "I"))
    }

    @Test
    fun diacriticsOnly_areAllowed() {
        assertTrue(allows("cafe", "café"))
        assertTrue(allows("resume", "résumé"))
        assertTrue(allows("naive", "naïve"))
    }

    @Test
    fun apostropheInserted_isAllowedWhenTheContractionIsCommoner() {
        assertTrue(allows("wed", "we'd"))
    }

    @Test
    fun apostropheInserted_isRefusedWhenTheTypedWordIsCommoner() {
        assertFalse(allows("well", "we'll"))
        assertFalse(allows("were", "we're"))
        assertFalse(allows("ill", "I'll"))
    }

    @Test
    fun apostropheInserted_tieProtectsTheTypedWord() {
        assertFalse(allows("its", "it's"))
    }

    @Test
    fun apostropheInserted_isAllowedWhenTheTableLacksASpelling() {
        // "cant" and "wont" are not in the LDB; only the contraction is. Nothing to weigh.
        assertTrue(allows("cant", "can't"))
        assertTrue(allows("wont", "won't"))
    }

    @Test
    fun apostropheInserted_isAllowedOutsideEnglish() {
        // The table is English; a French engine's pick stands.
        assertTrue(allows("well", "we'll", Locale.FRENCH))
    }

    @Test
    fun typographicApostrophe_countsAsAnApostrophe() {
        assertTrue(allows("wed", "we\u2019d"))
        assertFalse(allows("well", "we\u2019ll"))
    }

    @Test
    fun apostropheRemoved_isACaseOfSameLetters() {
        // Fewer apostrophes than typed: nothing was inserted, so it is "the same word".
        assertTrue(allows("it's", "its"))
    }

    @Test
    fun emptyInput_isRefused() {
        assertFalse(allows("", "a"))
        assertFalse(allows("a", ""))
        assertFalse(TypedWordGuard.allowsCorrection(null, "a", Locale.US))
    }
}
