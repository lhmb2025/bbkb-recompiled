package dev.bbkb.ime.keyboard.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The main-thread mirror of the engine's one swipe session. The engine's cancel clears the path
 * whatever pointer id it is given, so these rules keep a cancel meant for one contact from ending
 * another contact's swipe.
 */
class TouchSessionGateTest {

    private val gate = TouchSessionGate()

    @Test
    fun aTapIsAStartAndOneCancel() {
        gate.start(0)
        assertTrue(gate.move(0))
        assertTrue("the tap's own cancel", gate.cancel(0))
        assertFalse("the lift after it has nothing to decode", gate.end(0))
        assertFalse("and nothing more to cancel", gate.cancel(0))
    }

    @Test
    fun aSwipeIsAStartItsMovesAndOneEnd() {
        gate.start(0)
        assertTrue(gate.move(0))
        assertTrue(gate.move(0))
        assertTrue(gate.end(0))
        assertFalse("the session is over", gate.move(0))
        assertFalse("a second lift decodes nothing", gate.end(0))
    }

    @Test
    fun withoutAStartNothingIsPosted() {
        // Swiping is off for the field or the board: the trackers post no start.
        assertFalse(gate.move(0))
        assertFalse(gate.cancel(0))
        assertFalse(gate.end(0))
        assertEquals(TouchSessionGate.NOTHING, gate.cancelAny(longArrayOf(0, 1)))
    }

    @Test
    fun aCancelForAnotherContactNeverEndsTheSwipeInFlight() {
        gate.start(0)                       // the swiping finger
        assertFalse("a second finger's cancel", gate.cancel(1))
        assertFalse("its moves", gate.move(1))
        assertFalse("its lift", gate.end(1))
        assertTrue("the swipe carries on", gate.move(0))
        assertTrue(gate.end(0))
    }

    @Test
    fun aCancelForAnOlderContactNeverTrailsANewerContactsStart() {
        gate.start(0)                       // first finger lands
        gate.start(1)                       // second finger lands: newest wins
        assertFalse("the first finger lifting must not cancel the second's swipe", gate.cancel(0))
        assertFalse(gate.end(0))
        assertTrue(gate.move(1))
        assertTrue(gate.end(1))
    }

    @Test
    fun repeatedCancelsForOneContactPostOnce() {
        gate.start(0)
        assertTrue(gate.cancel(0))
        repeat(20) { assertFalse(gate.cancel(0)) }
    }

    @Test
    fun aCancelAfterAFinishedSwipeClearsItsPathOnce() {
        gate.start(0)
        gate.move(0)
        assertTrue(gate.end(0))
        // No session, but the decoded path is still in the engine's buffer: a cancel clears it, whoever
        // asks.
        assertTrue(gate.cancel(5))
        assertFalse(gate.cancel(5))
    }

    @Test
    fun aCancelForAllContactsOfAnEventNamesTheOwner() {
        gate.start(0)
        gate.start(1)                       // the second finger owns the session
        assertEquals("the owner, wherever it is in the event", 1L, gate.cancelAny(longArrayOf(0, 1)))
        assertEquals("once", TouchSessionGate.NOTHING, gate.cancelAny(longArrayOf(0, 1)))
    }

    @Test
    fun aCancelForAllContactsLeavesAnotherSurfacesSwipeAlone() {
        gate.start(3)                       // a contact that is not part of the event
        assertEquals(TouchSessionGate.NOTHING, gate.cancelAny(longArrayOf(0, 1)))
        assertTrue(gate.move(3))
    }

    @Test
    fun aCancelForAllContactsClearsAFinishedPath() {
        gate.start(0)
        gate.end(0)
        assertEquals(4L, gate.cancelAny(longArrayOf(4, 5)))
        assertEquals(TouchSessionGate.NOTHING, gate.cancelAny(longArrayOf(4, 5)))
        assertEquals("no contacts, nothing to ask for", TouchSessionGate.NOTHING, gate.cancelAny(longArrayOf()))
    }

    @Test
    fun aNewStartAfterACancelOpensAFreshSession() {
        gate.start(0)
        assertTrue(gate.cancel(0))
        gate.start(0)                       // the same pointer id, a new contact
        assertTrue(gate.move(0))
        assertTrue(gate.end(0))
    }

    @Test
    fun resetForgetsTheSession() {
        gate.start(0)
        gate.reset()
        assertFalse(gate.move(0))
        assertFalse(gate.cancel(0))
        assertFalse(gate.end(0))
    }
}
