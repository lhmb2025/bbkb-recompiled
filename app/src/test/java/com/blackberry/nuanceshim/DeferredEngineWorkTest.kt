package com.blackberry.nuanceshim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit P-7: the hand-over that keeps the main thread off the engine lock while a language load
 * holds it. Pure Java, so it runs without the engine's native library.
 */
class DeferredEngineWorkTest {

    private val work = NuanceSDK.DeferredEngineWork()
    private val ran = mutableListOf<String>()

    @Test
    fun nothingIsDeferredWithoutALongHold() {
        assertFalse(work.deferIfBusy("layout", Runnable { ran += "layout" }))
        assertFalse(work.hasPending())
    }

    @Test
    fun workIsHeldDuringALongHoldAndRunsInRequestOrder() {
        work.beginLongHold()
        assertTrue(work.deferIfBusy("layout", Runnable { ran += "layout" }))
        assertTrue(work.deferIfBusy("emoji", Runnable { ran += "emoji" }))
        assertTrue(work.isPending("layout"))
        work.endLongHold()
        assertTrue("ending the hold must not drop the work", work.hasPending())
        work.runPending()
        assertEquals(listOf("layout", "emoji"), ran)
        assertFalse(work.hasPending())
        assertFalse(work.isPending("layout"))
    }

    @Test
    fun aNewerRequestReplacesTheOlderOneAndMovesLast() {
        work.beginLongHold()
        work.deferIfBusy("layout", Runnable { ran += "layout-1" })
        work.deferIfBusy("emoji", Runnable { ran += "emoji" })
        work.deferIfBusy("layout", Runnable { ran += "layout-2" })
        work.endLongHold()
        work.runPending()
        assertEquals(listOf("emoji", "layout-2"), ran)
    }

    @Test
    fun discardDropsOnlyThatKey() {
        work.beginLongHold()
        work.deferIfBusy("layout", Runnable { ran += "layout" })
        work.deferIfBusy("emoji", Runnable { ran += "emoji" })
        work.endLongHold()
        work.discard("layout")
        assertTrue(work.hasPending())
        work.runPending()
        assertEquals(listOf("emoji"), ran)
    }

    @Test
    fun nestedHoldsDeferUntilTheLastEnds() {
        work.beginLongHold()
        work.beginLongHold()
        work.endLongHold()
        assertTrue(work.deferIfBusy("layout", Runnable { ran += "layout" }))
        work.endLongHold()
        assertFalse(work.deferIfBusy("emoji", Runnable { ran += "emoji" }))
    }

    @Test
    fun aFailingEntryDoesNotStopTheRest() {
        work.beginLongHold()
        work.deferIfBusy("bad", Runnable { throw IllegalStateException("boom") })
        work.deferIfBusy("emoji", Runnable { ran += "emoji" })
        work.endLongHold()
        work.runPending()
        assertEquals(listOf("emoji"), ran)
    }
}
