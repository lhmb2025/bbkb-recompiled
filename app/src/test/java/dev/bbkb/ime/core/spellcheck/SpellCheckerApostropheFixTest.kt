package dev.bbkb.ime.core.spellcheck

import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import dev.bbkb.ime.core.suggestion.PrevWordsInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Characterises the spell-checker session's apostrophe post-pass (`fixApostropheSuggestions`).
 *
 * For every word the per-word pass marked IN_THE_DICTIONARY that contains a straight apostrophe,
 * each non-empty apostrophe-separated part that is already in the suggestion cache (keyed with the
 * previous *in-dictionary* word) gets an empty override span appended after the original spans.
 * The override covers the part's own characters (its offset within the text, its length), and
 * carries the word's cookie/sequence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SpellCheckerApostropheFixTest {

    private data class W(val offset: Int, val length: Int, val attrs: Int, val cookie: Int = 0, val seq: Int = 0)

    private lateinit var session: AndroidSpellCheckerSession
    private lateinit var cache: Any

    @Before
    fun setUp() {
        val service = Robolectric.buildService(AndroidSpellCheckerService::class.java).get()
        session = AndroidSpellCheckerSession(service)
        cache = findField(session.javaClass, "mSuggestionCache").get(session)!!
    }

    @After
    fun tearDown() {
        session.onClose()
    }

    // ---- harness ---------------------------------------------------------------------------

    private fun findField(start: Class<*>, name: String): Field {
        var c: Class<*>? = start
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw NoSuchFieldException(name)
    }

    private fun findMethod(start: Class<*>, name: String): Method {
        var c: Class<*>? = start
        while (c != null) {
            c.declaredMethods.firstOrNull { it.name == name }?.let { return it.apply { isAccessible = true } }
            c = c.superclass
        }
        throw NoSuchMethodException(name)
    }

    private fun cached(word: String, prev: CharSequence?) {
        val put = cache.javaClass.getDeclaredMethod(
            "putCached", String::class.java, PrevWordsInfo::class.java,
            Array<String>::class.java, Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
        put.invoke(cache, word, PrevWordsInfo(PrevWordsInfo.WordInfo(prev)), arrayOf("cached"), 1)
    }

    private lateinit var input: SentenceSuggestionsInfo

    private fun fix(text: CharSequence, vararg words: W): SentenceSuggestionsInfo? {
        val infos = words.map { w ->
            SuggestionsInfo(w.attrs, arrayOf<String>()).apply { setCookieAndSequence(w.cookie, w.seq) }
        }.toTypedArray()
        input = SentenceSuggestionsInfo(
            infos, words.map { it.offset }.toIntArray(), words.map { it.length }.toIntArray()
        )
        val m = findMethod(session.javaClass, "fixApostropheSuggestions")
        return try {
            m.invoke(session, TextInfo(text, 0, text.length, 77, 88), input) as SentenceSuggestionsInfo?
        } catch (e: InvocationTargetException) {
            throw e.cause!!
        }
    }

    private fun render(out: SentenceSuggestionsInfo?): List<String>? = out?.let { s ->
        (0 until s.suggestionsCount).map { i ->
            val info = s.getSuggestionsInfoAt(i)
            "${s.getOffsetAt(i)}+${s.getLengthAt(i)} a=${info.suggestionsAttributes} " +
                "c=${info.cookie} s=${info.sequence} n=${info.suggestionsCount}"
        }
    }

    /** Asserts the first [n] spans of [out] are the original input objects, unchanged. */
    private fun assertOriginalsKept(out: SentenceSuggestionsInfo, n: Int) {
        for (i in 0 until n) {
            assertSame(input.getSuggestionsInfoAt(i), out.getSuggestionsInfoAt(i))
            assertEquals(input.getOffsetAt(i), out.getOffsetAt(i))
            assertEquals(input.getLengthAt(i), out.getLengthAt(i))
        }
    }

    private val IN = SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY
    private val TYPO = SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO
    private val REC = SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS

    // ---- no-op cases -----------------------------------------------------------------------

    @Test
    fun textWithoutAnyApostropheReturnsNull() {
        cached("dont", null)
        assertNull(fix("dont", W(0, 4, IN)))
    }

    @Test
    fun curlyApostropheSplitsLikeTheStraightOne() {
        // U+2019 is an apostrophe here, matching the per-word normalisation.
        cached("don", null)
        cached("t", null)
        assertEquals(
            listOf("0+5 a=1 c=0 s=0 n=0", "0+3 a=0 c=0 s=0 n=0", "4+1 a=0 c=0 s=0 n=0"),
            render(fix("don’t", W(0, 5, IN)))
        )
    }

    @Test
    fun curlyWordNextToAStraightApostropheWordIsProcessed() {
        cached("don", null)
        cached("it", null)
        // "it's" is not in the dictionary (skipped, and not the previous word); "don’t" is processed.
        assertEquals(
            listOf("0+4 a=2 c=0 s=0 n=0", "5+5 a=1 c=0 s=0 n=0", "5+3 a=0 c=0 s=0 n=0"),
            render(fix("it's don’t", W(0, 4, TYPO), W(5, 5, IN)))
        )
    }

    @Test
    fun mixedApostrophesInOneWordSplitAtBoth() {
        cached("rock", null)
        cached("n", null)
        cached("roll", null)
        assertEquals(
            listOf(
                "0+11 a=1 c=0 s=0 n=0",
                "0+4 a=0 c=0 s=0 n=0", "5+1 a=0 c=0 s=0 n=0", "7+4 a=0 c=0 s=0 n=0"
            ),
            render(fix("rock’n'roll", W(0, 11, IN)))
        )
    }

    @Test
    fun wordNotMarkedInDictionaryIsSkipped() {
        cached("don", null)
        assertNull(fix("don't", W(0, 5, TYPO)))
        assertNull(fix("don't", W(0, 5, 0)))
        assertNull(fix("don't", W(0, 5, TYPO or REC)))
    }

    @Test
    fun uncachedPartsProduceNothing() {
        assertNull(fix("don't", W(0, 5, IN)))
    }

    @Test
    fun apostropheOnlyWordsHaveNoNonEmptyParts() {
        cached("x", null)
        assertNull(fix("'", W(0, 1, IN)))
        assertNull(fix("''", W(0, 2, IN)))
    }

    @Test
    fun sentenceWithNoSpansReturnsNull() {
        cached("don", null)
        assertNull(fix("don't"))
    }

    // ---- emission --------------------------------------------------------------------------

    @Test
    fun cachedPartGetsAnEmptyOverrideSpanCarryingTheWordsCookieAndSequence() {
        cached("don", null)
        val out = fix("don't", W(0, 5, IN, cookie = 3, seq = 4))!!
        assertEquals(listOf("0+5 a=1 c=3 s=4 n=0", "0+3 a=0 c=3 s=4 n=0"), render(out))
        assertOriginalsKept(out, 1)
    }

    @Test
    fun inDictionaryBitAmongOtherFlagsCounts() {
        cached("don", null)
        assertEquals(
            listOf("0+5 a=5 c=0 s=0 n=0", "0+3 a=0 c=0 s=0 n=0"),
            render(fix("don't", W(0, 5, IN or REC)))
        )
    }

    @Test
    fun everyCachedPartIsEmittedAtItsOwnOffset() {
        // Each override covers its own part's characters within the word.
        cached("don", null)
        cached("t", null)
        assertEquals(
            listOf("0+5 a=1 c=0 s=0 n=0", "0+3 a=0 c=0 s=0 n=0", "4+1 a=0 c=0 s=0 n=0"),
            render(fix("don't", W(0, 5, IN)))
        )
    }

    @Test
    fun secondPartAloneIsPlacedAtItsOwnOffset() {
        cached("t", null)
        assertEquals(
            listOf("4+5 a=1 c=0 s=0 n=0", "8+1 a=0 c=0 s=0 n=0"),
            render(fix("hey don't", W(4, 5, IN)))
        )
    }

    @Test
    fun leadingApostrophe() {
        cached("tis", null)
        assertEquals(listOf("0+4 a=1 c=0 s=0 n=0", "1+3 a=0 c=0 s=0 n=0"), render(fix("'tis", W(0, 4, IN))))
    }

    @Test
    fun trailingApostrophe() {
        cached("dogs", null)
        assertEquals(listOf("0+5 a=1 c=0 s=0 n=0", "0+4 a=0 c=0 s=0 n=0"), render(fix("dogs'", W(0, 5, IN))))
    }

    @Test
    fun multipleApostrophesEmitInPartOrder() {
        cached("rock", null)
        cached("n", null)
        cached("roll", null)
        assertEquals(
            listOf(
                "0+11 a=1 c=0 s=0 n=0",
                "0+4 a=0 c=0 s=0 n=0", "5+1 a=0 c=0 s=0 n=0", "7+4 a=0 c=0 s=0 n=0"
            ),
            render(fix("rock'n'roll", W(0, 11, IN)))
        )
    }

    @Test
    fun doubledApostropheSkipsTheEmptyMiddlePart() {
        cached("a", null)
        cached("b", null)
        assertEquals(
            listOf("0+4 a=1 c=0 s=0 n=0", "0+1 a=0 c=0 s=0 n=0", "3+1 a=0 c=0 s=0 n=0"),
            render(fix("a''b", W(0, 4, IN)))
        )
    }

    @Test
    fun surrogatePairBeforeTheWordShiftsOffsetsInUtf16Units() {
        cached("don", null)
        assertEquals(
            listOf("3+5 a=1 c=0 s=0 n=0", "3+3 a=0 c=0 s=0 n=0"),
            render(fix("😀 don't", W(3, 5, IN)))
        )
    }

    @Test
    fun surrogatePairInsideAPartCountsAsTwoUnitsOfLength() {
        cached("a😀", null)
        assertEquals(
            listOf("0+5 a=1 c=0 s=0 n=0", "0+3 a=0 c=0 s=0 n=0"),
            render(fix("a😀'b", W(0, 5, IN)))
        )
    }

    // ---- previous-word keying --------------------------------------------------------------

    @Test
    fun cacheLookupIsKeyedWithThePreviousInDictionaryWord() {
        cached("don", "we")
        assertEquals(
            listOf("0+2 a=1 c=0 s=0 n=0", "3+5 a=1 c=0 s=0 n=0", "3+3 a=0 c=0 s=0 n=0"),
            render(fix("we don't", W(0, 2, IN), W(3, 5, IN)))
        )
    }

    @Test
    fun entryCachedWithoutPreviousWordDoesNotMatchWhenThereIsOne() {
        cached("don", null)
        assertNull(fix("we don't", W(0, 2, IN), W(3, 5, IN)))
    }

    @Test
    fun firstInDictionaryWordHasNoPreviousWordEvenAfterAnUnflaggedWord() {
        cached("don", null)
        assertEquals(
            listOf("0+2 a=2 c=0 s=0 n=0", "3+5 a=1 c=0 s=0 n=0", "3+3 a=0 c=0 s=0 n=0"),
            render(fix("we don't", W(0, 2, TYPO), W(3, 5, IN)))
        )
    }

    @Test
    fun wordsNotInTheDictionaryDoNotBecomeThePreviousWord() {
        cached("don", "we")
        assertEquals(
            listOf(
                "0+2 a=1 c=0 s=0 n=0", "3+3 a=2 c=0 s=0 n=0", "7+5 a=1 c=0 s=0 n=0",
                "7+3 a=0 c=0 s=0 n=0"
            ),
            render(fix("we can don't", W(0, 2, IN), W(3, 3, TYPO), W(7, 5, IN)))
        )
    }

    @Test
    fun unflaggedWordIsNeverUsedAsPreviousWord() {
        cached("don", "can")
        assertNull(fix("we can don't", W(0, 2, IN), W(3, 3, TYPO), W(7, 5, IN)))
    }

    @Test
    fun previousWordIsTheWholeFlaggedWordApostropheIncluded() {
        cached("won", "don't")
        assertEquals(
            listOf("0+5 a=1 c=0 s=0 n=0", "6+5 a=1 c=0 s=0 n=0", "6+3 a=0 c=0 s=0 n=0"),
            render(fix("don't won't", W(0, 5, IN), W(6, 5, IN)))
        )
    }

    @Test
    fun overridesAreAppendedAfterAllOriginalsInWordOrder() {
        cached("it", null)
        cached("don", "it's")
        val out = fix(
            "it's don't ok",
            W(0, 4, IN, cookie = 1, seq = 11), W(5, 5, IN, cookie = 2, seq = 22), W(11, 2, IN, cookie = 3, seq = 33)
        )!!
        assertEquals(
            listOf(
                "0+4 a=1 c=1 s=11 n=0", "5+5 a=1 c=2 s=22 n=0", "11+2 a=1 c=3 s=33 n=0",
                "0+2 a=0 c=1 s=11 n=0", "5+3 a=0 c=2 s=22 n=0"
            ),
            render(out)
        )
        assertOriginalsKept(out, 3)
    }
}
