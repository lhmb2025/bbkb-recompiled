package dev.bbkb.ime.core.gesture.arbiter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [CkbKeyGrid.keyAt] — the normalized-coordinate hit test used by the
 * new-engine gesture path and the Gesture Lab overlay. Pure JVM, no Robolectric needed.
 */
class CkbKeyGridTest {

    // Two adjacent keys on one row: q centered at (0.05, 0.17), w at (0.15, 0.17),
    // each 0.1 wide and 0.33 tall (athena-like normalized geometry).
    private val q = CkbKey("q", 0.05f, 0.17f, 0.10f, 0.33f)
    private val w = CkbKey("w", 0.15f, 0.17f, 0.10f, 0.33f)
    private val cells = listOf(q, w)

    @Test
    fun pointInsideCell_hits() {
        assertEquals(q, CkbKeyGrid.keyAt(cells, 0.05f, 0.17f))
        assertEquals(w, CkbKeyGrid.keyAt(cells, 0.15f, 0.20f))
    }

    @Test
    fun pointOutsideAllCells_misses() {
        assertNull(CkbKeyGrid.keyAt(cells, 0.90f, 0.90f))
        assertNull(CkbKeyGrid.keyAt(cells, 0.05f, 0.80f))
    }

    @Test
    fun cellEdges_areInclusive() {
        // q spans x in [0.0, 0.1], y in [0.005, 0.335]
        assertEquals(q, CkbKeyGrid.keyAt(cells, 0.0f, 0.17f))
        assertEquals(q, CkbKeyGrid.keyAt(cells, 0.05f, 0.335f))
    }

    @Test
    fun sharedBoundary_firstCellWins() {
        // x = 0.1 is q's right edge and w's left edge; list order decides.
        assertEquals(q, CkbKeyGrid.keyAt(cells, 0.10f, 0.17f))
    }

    @Test
    fun emptyGrid_returnsNull() {
        assertNull(CkbKeyGrid.keyAt(emptyList(), 0.5f, 0.5f))
    }

    @Test
    fun aspectMatchesAthenaKeypadDimensions() {
        assertEquals(1080f / 525f, CkbKeyGrid.ASPECT, 1e-6f)
    }
}
