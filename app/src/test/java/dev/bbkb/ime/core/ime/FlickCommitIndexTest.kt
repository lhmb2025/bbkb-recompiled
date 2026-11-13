package dev.bbkb.ime.core.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins `flickCommitIndex`: the strip shows suggestion i in slot i, so the slot under the flick is
 * the list index. With one suggestion it must be 0, never a "centre slot" 1 that would overflow.
 */
class FlickCommitIndexTest {

    @Test
    fun oneSuggestion_isIndexZero_whereverTheFlickStarts() {
        for (x in listOf(0f, 0.2f, 0.5f, 0.8f, 1f)) {
            assertEquals("x=$x", 0, flickCommitIndex(x, 1))
        }
    }

    @Test
    fun threeSuggestions_mapLeftCentreRightOntoIndices012() {
        assertEquals(0, flickCommitIndex(0.1f, 3))
        assertEquals(1, flickCommitIndex(0.5f, 3))
        assertEquals(2, flickCommitIndex(0.9f, 3))
    }

    @Test
    fun slotBoundaries_fallOnThirds() {
        assertEquals(0, flickCommitIndex(0.33f, 3))
        assertEquals(1, flickCommitIndex(0.34f, 3))
        assertEquals(1, flickCommitIndex(0.66f, 3))
        assertEquals(2, flickCommitIndex(0.67f, 3))
    }

    @Test
    fun flickOverAnEmptySlot_commitsTheLastVisibleWord() {
        assertEquals(1, flickCommitIndex(0.9f, 2))
    }

    @Test
    fun moreThanThreeSuggestions_neverReachPastTheStrip() {
        assertEquals(2, flickCommitIndex(1f, 7))
    }

    @Test
    fun outOfRangeX_isClamped() {
        assertEquals(0, flickCommitIndex(-0.5f, 3))
        assertEquals(2, flickCommitIndex(1.5f, 3))
    }

    @Test
    fun noSuggestions_isNothingToCommit() {
        assertEquals(-1, flickCommitIndex(0.5f, 0))
        assertEquals(-1, flickCommitIndex(0.5f, -1))
    }
}
