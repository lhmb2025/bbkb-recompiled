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
 * Regression tests for audit finding UT-2.
 *
 * The case-transform guard in [SuggestionEngine.applyCaseTransform] was a tautology
 * (`firstCaps || allCaps || (!firstCaps && !allCaps)`), so the loop always ran and
 * [SuggestionEngine.transformCase] allocated a StringBuilder, a String and a fresh
 * SuggestedWordInfo for every entry of every suggestion request — up to 18 entries per
 * keystroke, 150 for CJK — even when the case was already correct.
 *
 * `transformCase` now returns its argument unchanged when nothing needs transforming.
 * These tests pin BOTH halves: the identity fast path, and that every case that does need
 * a transform still produces exactly what it produced before.
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
        forceLowercase: Boolean = false,
        trailingApostrophes: Int = 0,
    ) = SuggestionEngine.transformCase(info(word), en, allCaps, firstCaps, forceLowercase, trailingApostrophes)

    // ── the fast path: nothing to do, so nothing is allocated ────────────────

    @Test
    fun alreadyLowercase_withForceLowercase_returnsTheSameInstance() {
        // The per-keystroke common case: forceLowercase is on (it is `!firstCaps && !allCaps`,
        // i.e. on for most keystrokes) but the word does not start with an upper-case letter.
        val input = info("hello")
        assertSame(input, SuggestionEngine.transformCase(input, en, false, false, true, 0))
    }

    @Test
    fun noFlagsAtAll_returnsTheSameInstance() {
        val input = info("Hello")
        assertSame(input, SuggestionEngine.transformCase(input, en, false, false, false, 0))
    }

    @Test
    fun emptyWord_returnsTheSameInstance() {
        val input = info("")
        assertSame(input, SuggestionEngine.transformCase(input, en, false, false, true, 0))
    }

    // ── every branch that DOES transform still transforms ────────────────────

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
    fun forceLowercase_stillLowercasesALeadingCapital() {
        val input = info("Hello")
        val out = SuggestionEngine.transformCase(input, en, false, false, true, 0)
        assertNotSame(input, out)
        assertEquals("hello", out.word)
    }

    @Test
    fun trailingApostrophes_areStillAppended() {
        // The apostrophe loop counts down from
        // `trailingApostrophes - (word already contains one ? 1 : 0) - 1`, so two trailing
        // apostrophes on a word without one appends two.
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
        val out = SuggestionEngine.transformCase(input, en, true, false, false, 0)
        assertNotSame(input, out)
        assertEquals("HELLO", out.word)
        assertEquals(42, out.score)
        assertEquals(7, out.kindAndFlags)
        assertEquals(3, out.indexInDictionary)
        assertEquals(5, out.indexInSuggestions)
    }
}
