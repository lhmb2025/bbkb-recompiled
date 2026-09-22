package dev.bbkb.ime.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the divide-by-zero hole in [ProximityGrid] — the grid behind `Keyboard.getNearestKeys`
 * → `KeyDetector.detectHitKey`, i.e. the key-hit test for every touch.
 *
 * The constructor derives the grid cell size by dividing the occupied width/height by the grid
 * width/height. The guard that was supposed to prevent a divide-by-zero sat BELOW those two
 * divisions and tested the occupied dimensions, not the divisors — so a keyboard built with a
 * zero grid dimension threw `ArithmeticException` out of the `Keyboard` constructor before the
 * guard was ever reached. MainKeyboardView documents that crash as a real observed failure on the
 * physical pad (it was worked around there by choosing a different source keyboard for
 * `MoreKeysKeyboard` geometry); the hole stayed open for every other caller, including
 * `KeyboardBuilder.createFromLabels`, which takes its key height straight from its caller.
 *
 * The grid must simply come out empty for a degenerate keyboard, and lookups against it must not
 * divide by the (now zero) cell size either.
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

    /** The original guard's case: a moreKeys keyboard against a zero-height parent. */
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
