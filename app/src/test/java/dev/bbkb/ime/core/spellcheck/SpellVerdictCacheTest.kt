package dev.bbkb.ime.core.spellcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpellVerdictCacheTest {

    private var now = 0L
    private val cache = SpellVerdictCache { now }

    @Test
    fun answersAreKeyedByWordAndSettings() {
        cache.put("hello", 1, true)
        cache.put("hello", 0, false)
        assertEquals(true, cache.get("hello", 1))
        assertEquals(false, cache.get("hello", 0))
        assertNull(cache.get("hello", 3))
        assertNull(cache.get("Hello", 1))
    }

    @Test
    fun aNotAWordAnswerExpiresAfterTwoMinutes() {
        // A word the keyboard learns stops being underlined as soon as it did before.
        cache.put("bbkb", 1, false)
        now += SpellVerdictCache.NEGATIVE_MAX_AGE_MS
        assertEquals(false, cache.get("bbkb", 1))
        now += 1
        assertNull(cache.get("bbkb", 1))
    }

    @Test
    fun aWordAnswerLastsMuchLonger() {
        cache.put("hello", 1, true)
        now += SpellVerdictCache.NEGATIVE_MAX_AGE_MS + 1
        assertEquals(true, cache.get("hello", 1))
        now += SpellVerdictCache.POSITIVE_MAX_AGE_MS - SpellVerdictCache.NEGATIVE_MAX_AGE_MS - 1
        assertEquals(true, cache.get("hello", 1))
        now += 1
        assertNull(cache.get("hello", 1))
    }

    @Test
    fun aNewEngineGenerationEmptiesTheCache() {
        cache.syncDictionaryState(0)
        cache.put("hello", 1, true)
        cache.syncDictionaryState(0)
        assertEquals(true, cache.get("hello", 1))
        cache.syncDictionaryState(1)
        assertNull(cache.get("hello", 1))
    }

    @Test
    fun aDifferentDictionaryStateEmptiesTheCache() {
        cache.syncDictionaryState("en")
        cache.put("hello", 1, true)
        cache.syncDictionaryState("en")
        assertEquals(true, cache.get("hello", 1))
        cache.syncDictionaryState("fr")
        assertNull(cache.get("hello", 1))
    }

    @Test
    fun theLeastRecentlyUsedAnswerGoesFirst() {
        for (i in 0 until SpellVerdictCache.MAX_ENTRIES) cache.put("w$i", 0, true)
        cache.get("w0", 0)
        cache.put("extra", 0, true)
        assertEquals(SpellVerdictCache.MAX_ENTRIES, cache.size())
        assertEquals(true, cache.get("w0", 0))
        assertNull(cache.get("w1", 0))
    }
}
