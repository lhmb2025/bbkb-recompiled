package dev.bbkb.ime.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins that [ProximityGrid] (the key-hit grid behind `Keyboard.getNearestKeys`) comes out empty for
 * a zero or negative grid dimension instead of throwing `ArithmeticException` from the `Keyboard`
 * constructor, and that lookups do not divide by the zero cell size.
 */
class ProximityGridDegenerateTest {

    private fun grid(gridWidth: Int, gridHeight: Int, occupiedWidth: Int, occupiedHeight: Int) =
        ProximityGrid(
            gridWidth, gridHeight, occupiedWidth, occupiedHeight,
            /* mostCommonKeyWidth = */ 40, emptyList()
        )

    @Test
    fun zeroGridWidthDoesNotThrow() {
        val g = grid(gridWidth = 0, gridHeight = 4, occupiedWidth = 1080, occupiedHeight = 600)
        assertTrue("degenerate grid must be empty", g.getKeysInColumns(10, 10).isEmpty())
    }

    @Test
    fun zeroGridHeightDoesNotThrow() {
        val g = grid(gridWidth = 4, gridHeight = 0, occupiedWidth = 1080, occupiedHeight = 600)
        assertTrue("degenerate grid must be empty", g.getKeysInColumns(10, 10).isEmpty())
    }

    /** A moreKeys keyboard against a zero-height parent. */
    @Test
    fun negativeOccupiedHeightDoesNotThrow() {
        val g = grid(gridWidth = 4, gridHeight = 4, occupiedWidth = 1080, occupiedHeight = -9)
        assertTrue("degenerate grid must be empty", g.getKeysInColumns(10, 10).isEmpty())
    }

    /** A well-formed grid still answers, and still rejects out-of-bounds coordinates. */
    @Test
    fun wellFormedGridAnswersWithinBoundsAndRejectsOutside() {
        val g = grid(gridWidth = 4, gridHeight = 4, occupiedWidth = 1080, occupiedHeight = 600)
        // No keys were supplied, so every in-bounds cell is an empty (but non-null) list.
        assertTrue(g.getKeysInColumns(10, 10).isEmpty())
        assertTrue(g.getKeysInColumns(5000, 10).isEmpty())
        assertTrue(g.getKeysInColumns(10, -1).isEmpty())
    }
}
