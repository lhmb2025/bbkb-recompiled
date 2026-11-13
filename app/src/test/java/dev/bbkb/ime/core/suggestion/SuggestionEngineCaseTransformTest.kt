package dev.bbkb.ime.core.suggestion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * [SuggestionEngine.transformCase] returns its argument unchanged when the case is already right
 * (no allocation per entry), and every case that needs a transform still produces the same output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SuggestionEngineCaseTransformTest {

    private val en = Locale.ENGLISH

    private fun info(word: String) =
        SuggestedWords.SuggestedWordInfo(word, 100, 1, null, -1, -1, null)

    private fun transform(
        word: String,
        allCaps: Boolean = false,
        firstCaps: Boolean = false,
        trailingApostrophes: Int = 0,
    ) = SuggestionEngine.transformCase(info(word), en, allCaps, firstCaps, trailingApostrophes)

    @Test
    fun noFlagsAtAll_returnsTheSameInstance() {
        val input = info("hello")
        assertSame(input, SuggestionEngine.transformCase(input, en, false, false, 0))
    }

    @Test
    fun emptyWord_returnsTheSameInstance() {
        val input = info("")
        assertSame(input, SuggestionEngine.transformCase(input, en, false, false, 0))
    }

    @Test
    fun capitalizedWord_midSentence_keepsItsCapitals() {
        // A capital the dictionary spells (BlackBerry, personal-dictionary names) is never lowered.
        val input = info("BlackBerry")
        val out = SuggestionEngine.transformCase(input, en, false, false, 0)
        assertSame(input, out)
        assertEquals("BlackBerry", out.word)
    }

    @Test
    fun allCaps_stillUppercases() {
        val out = transform("hello", allCaps = true)
        assertEquals("HELLO", out.word)
    }

    @Test
    fun firstCaps_stillCapitalizes() {
        val out = transform("hello", firstCaps = true)
        assertEquals("Hello", out.word)
    }

    @Test
    fun trailingApostrophes_areStillAppended() {
        // The apostrophe loop counts down from `trailingApostrophes - (has one ? 1 : 0) - 1`, so two
        // trailing apostrophes on a word without one appends two.
        val out = transform("dont", trailingApostrophes = 2)
        assertEquals("dont''", out.word)
    }

    @Test
    fun trailingApostrophes_onAWordThatAlreadyHasOne_appendNothingExtra() {
        val out = transform("don't", trailingApostrophes = 1)
        assertEquals("don't", out.word)
    }

    @Test
    fun transformedResult_carriesTheOriginalMetadata() {
        val input = SuggestedWords.SuggestedWordInfo("hello", 42, 7, null, 3, 5, null)
        val out = SuggestionEngine.transformCase(input, en, true, false, 0)
        assertNotSame(input, out)
        assertEquals("HELLO", out.word)
        assertEquals(42, out.score)
        assertEquals(7, out.kindAndFlags)
        assertEquals(3, out.indexInDictionary)
        assertEquals(5, out.indexInSuggestions)
    }
}
