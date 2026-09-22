package dev.bbkb.ime.core.engine

import dev.bbkb.ime.core.textinput.composing.ComposingTextTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * CHARACTERISATION of [FallbackDictionary] — Wave 3b package G1.
 *
 * Expected rows were computed by an independent re-implementation over the literal word array, not
 * copied from the class. Each row is (word, score, kind): kind 8 = prediction (empty input),
 * 0 = exact, 2 = prefix, 1 = contains-only. Score = matchCount - position.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class FallbackDictionaryTest {

    private val dict = FallbackDictionary(null, null)

    private fun tracker(text: String?) =
        mock(ComposingTextTracker::class.java).also { `when`(it.composingText).thenReturn(text) }

    private fun rows(d: FallbackDictionary, text: String?, max: Int) =
        d.generateSuggestions(tracker(text), null, null, max).map { Triple(it.word, it.score, it.kind) }

    @Test
    fun nullTracker_isEmpty() {
        assertTrue(dict.generateSuggestions(null, null, null, 0).isEmpty())
    }

    @Test
    fun emptyOrNullInput_offersTheFirstThreeWordsAsPredictions() {
        val expected = listOf(Triple("the", 149, 8), Triple("and", 148, 8), Triple("for", 147, 8))
        assertEquals(expected, rows(dict, "", 0))
        assertEquals(expected, rows(dict, null, 5))
    }

    @Test
    fun prefixMatches_areCappedAtEight() {
        val expected = listOf(
            Triple("the", 8, 2), Triple("than", 7, 2), Triple("that", 6, 2), Triple("their", 5, 2),
            Triple("there", 4, 2), Triple("these", 3, 2), Triple("they", 2, 2), Triple("thing", 1, 2),
        )
        assertEquals(expected, rows(dict, "th", 0))
        assertEquals(expected, rows(dict, "th", 20))
    }

    @Test
    fun positiveMaxBelowEight_isHonoured() {
        assertEquals(
            listOf(Triple("the", 3, 2), Triple("than", 2, 2), Triple("that", 1, 2)),
            rows(dict, "th", 3),
        )
    }

    @Test
    fun negativeMax_meansEight_andContainsPassFillsAfterPrefixes() {
        assertEquals(
            listOf(
                Triple("information", 8, 2), Triple("interest", 7, 2), Triple("again", 6, 1),
                Triple("being", 5, 1), Triple("point", 4, 1), Triple("thing", 3, 1),
                Triple("think", 2, 1), Triple("line", 1, 1),
            ),
            rows(dict, "in", -5),
        )
    }

    @Test
    fun singleCharacterInput_skipsTheContainsPass() {
        assertEquals(
            listOf(
                Triple("and", 6, 2), Triple("are", 5, 2), Triple("all", 4, 2),
                Triple("about", 3, 2), Triple("after", 2, 2), Triple("again", 1, 2),
            ),
            rows(dict, "a", 0),
        )
    }

    @Test
    fun containsPass_appendsInArrayOrder() {
        assertEquals(
            listOf(
                Triple("our", 8, 2), Triple("out", 7, 2), Triple("you", 6, 1), Triple("about", 5, 1),
                Triple("could", 4, 1), Triple("found", 3, 1), Triple("group", 2, 1), Triple("house", 1, 1),
            ),
            rows(dict, "ou", 0),
        )
    }

    @Test
    fun exactMatch_isKindZero_caseInsensitively() {
        assertEquals(
            listOf(
                Triple("the", 6, 0), Triple("their", 5, 2), Triple("there", 4, 2),
                Triple("these", 3, 2), Triple("they", 2, 2), Triple("other", 1, 1),
            ),
            rows(dict, "The", 0),
        )
    }

    @Test
    fun duplicateArrayEntries_appearOnce() {
        // "need" is listed twice in the array.
        assertEquals(listOf(Triple("need", 1, 2)), rows(dict, "nee", 0))
    }

    @Test
    fun noMatch_isEmpty() {
        assertTrue(rows(dict, "xyz", 0).isEmpty())
    }

    @Test
    fun suggestionProvenance() {
        val info = dict.generateSuggestions(tracker("wa"), null, null, 0)
        assertEquals(listOf("was", "want", "water", "way"), info.map { it.word })
        info.forEach {
            assertSame(dict, it.sourceDictionary)
            assertEquals(-1, it.indexInDictionary)
            assertEquals(Int.MAX_VALUE, it.indexInSuggestions)
        }
        val pred = dict.generateSuggestions(tracker(""), null, null, 0)
        pred.forEach { assertSame(dict, it.sourceDictionary) }
    }

    @Test
    fun locale_isUsedForLowerCasing_andNullMeansEnglish() {
        // Turkish lower-cases "IN" to "ın", which matches nothing.
        val tr = FallbackDictionary(null, Locale("tr"))
        assertTrue(rows(tr, "IN", 0).isEmpty())
        assertFalse(tr.isWordValid("IN"))
        assertEquals("information", rows(dict, "IN", 0).first().first)
    }

    @Test
    fun isWordValid() {
        assertFalse(dict.isWordValid(null))
        assertFalse(dict.isWordValid(""))
        assertTrue(dict.isWordValid("THE"))
        assertTrue(dict.isValidWord("without"))
        assertFalse(dict.isWordValid("zebra"))
    }

    @Test
    fun fixedAnswers() {
        assertEquals("main", dict.dictType)
        assertTrue(dict.isInitialized)
        assertEquals(" ", dict.getWordSeparator("anything"))
        assertFalse(dict.onWordChanged("a", 0, "b", "c"))
        assertFalse(dict.addLocales(setOf(Locale.FRENCH)))
    }
}
