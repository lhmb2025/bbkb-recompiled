package dev.bbkb.ime.core.suggestion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A pick from the editor's popup teaches the engine only when we offered that alternative. */
class SuggestionPickLearnerTest {

    private val learned = mutableListOf<String>()
    private lateinit var originalSink: (String) -> Boolean

    @Before
    fun setUp() {
        originalSink = SuggestionPickLearner.sink
        SuggestionPickLearner.sink = { learned.add(it); true }
        SuggestionPickLearner.clearForTest()
    }

    @After
    fun tearDown() {
        SuggestionPickLearner.sink = originalSink
        SuggestionPickLearner.clearForTest()
    }

    @Test
    fun aPickOfAnOfferedAlternative_isLearned() {
        SuggestionPickLearner.remember("teh", listOf("the", "tea"))
        assertTrue(SuggestionPickLearner.onPicked("teh", "the"))
        assertEquals(listOf("the"), learned)
    }

    @Test
    fun aPickWeNeverOffered_isIgnored() {
        SuggestionPickLearner.remember("teh", listOf("the", "tea"))
        assertFalse(SuggestionPickLearner.onPicked("teh", "pwned"))
        assertFalse(SuggestionPickLearner.onPicked("unknown", "the"))
        assertTrue(learned.isEmpty())
    }

    @Test
    fun theSameWordOrEmptyExtras_areIgnored() {
        SuggestionPickLearner.remember("teh", listOf("the"))
        assertFalse(SuggestionPickLearner.onPicked("teh", "teh"))
        assertFalse(SuggestionPickLearner.onPicked(null, "the"))
        assertFalse(SuggestionPickLearner.onPicked("teh", ""))
        assertTrue(learned.isEmpty())
    }

    @Test
    fun onlyTheMostRecentWordsAreRemembered() {
        for (i in 0 until 40) SuggestionPickLearner.remember("w$i", listOf("a$i"))
        assertFalse("the oldest entry must have been evicted", SuggestionPickLearner.onPicked("w0", "a0"))
        assertTrue(SuggestionPickLearner.onPicked("w39", "a39"))
    }
}
