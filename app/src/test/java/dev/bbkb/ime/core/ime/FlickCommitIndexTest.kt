package dev.bbkb.ime.core.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `flickCommitIndex` maps a CKB flick-up onto the suggestion it commits. The strip shows
 * suggestion i in slot i, left to right, so the slot under the flick IS the list index.
 *
 * The first test is the crash from the 2026-09-27 KEY2 pass: with one suggestion showing, the
 * flick was routed to "the centre slot", index 1, and `SuggestedWords.getWordInfo(1)` threw on a
 * one-element list on every flick-up.
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
