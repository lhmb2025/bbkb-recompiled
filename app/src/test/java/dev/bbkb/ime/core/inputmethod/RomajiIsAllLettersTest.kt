package dev.bbkb.ime.core.inputmethod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit DK-22: `isAllLetters` was decompiled as `(c <= 'a' || c >= 'z') && (c <= 'A' || c >= 'Z')`,
 * so the four boundary letters themselves satisfied the reject condition. `isAllLetters` gates the
 * "prefer the user's typed text over the engine's inline word" rule in `RomajiInputProcessor`, so
 * any Romaji word containing an `a` or a `z` silently fell back to the NuanceSDK inline word.
 */
class RomajiIsAllLettersTest {

    private val isAllLetters = RomajiInputProcessor::class.java
        .getDeclaredMethod("isAllLetters", String::class.java)
        .apply { isAccessible = true }

    private fun check(s: String): Boolean = isAllLetters.invoke(null, s) as Boolean

    @Test fun boundaryLettersAreLetters() {
        for (s in listOf("a", "z", "A", "Z", "az", "AZ")) {
            assertTrue("'$s' is all letters", check(s))
        }
    }

    @Test fun ordinaryWordsWithBoundaryLettersAreLetters() {
        assertTrue(check("Amazon"))
        assertTrue(check("za"))
        assertTrue(check("kaizen"))
    }

    @Test fun midRangeLettersStillWork() {
        assertTrue(check("hm"))
        assertTrue(check("Hm"))
    }

    @Test fun nonLettersRejected() {
        assertFalse(check("a1"))
        assertFalse(check("a "))
        assertFalse(check("a-b"))
        assertFalse(check("あ"))
    }

    // The empty-string case is deliberately not tested here: it is decided by
    // android.text.TextUtils.isEmpty, which is a non-functional stub in plain JVM unit tests.
}
