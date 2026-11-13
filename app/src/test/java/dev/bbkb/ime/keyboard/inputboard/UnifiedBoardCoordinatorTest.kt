package dev.bbkb.ime.keyboard.inputboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [UnifiedBoardCoordinator]'s toggle core against a recording host. The UIM's view state can be
 * cleared between a board key's down and up, so a second press must close from the coordinator's
 * own memory, never reopen.
 */
class UnifiedBoardCoordinatorTest {

    /**
     * Honors the BoardHost contract: reports the resulting state via notifyBoardOpened/notifyBoardClosed
     * (production: UnifiedInputBoardManager.setActiveComponent). The coordinator never assumes success.
     */
    private class FakeHost : UnifiedBoardCoordinator.BoardHost {
        lateinit var coordinator: UnifiedBoardCoordinator
        val opened = mutableListOf<Int>()
        val closed = mutableListOf<Int>()

        override fun openBoard(keyCode: Int) {
            opened.add(keyCode)
            coordinator.notifyBoardOpened(keyCode)
        }

        override fun closeBoard(keyCode: Int) {
            closed.add(keyCode)
            coordinator.notifyBoardClosed()
        }
    }

    private fun coordinatorWith(host: FakeHost): UnifiedBoardCoordinator {
        val coordinator = UnifiedBoardCoordinator(host)
        host.coordinator = coordinator
        return coordinator
    }

    @Test
    fun requestBoard_opensWhenNoneActive() {
        val host = FakeHost()
        val coordinator = coordinatorWith(host)

        coordinator.requestBoard(CLIPBOARD)

        assertEquals(listOf(CLIPBOARD), host.opened)
        assertTrue(host.closed.isEmpty())
        assertEquals(CLIPBOARD, coordinator.activeBoard())
    }

    /** Two presses of the same board key: the second closes, decided from [activeBoard] alone. */
    @Test
    fun requestBoard_secondPressClosesNeverReopens() {
        val host = FakeHost()
        val coordinator = coordinatorWith(host)

        coordinator.requestBoard(CLIPBOARD) // open
        coordinator.requestBoard(CLIPBOARD) // must close, not reopen

        assertEquals("opened exactly once", listOf(CLIPBOARD), host.opened)
        assertEquals("closed exactly once", listOf(CLIPBOARD), host.closed)
        assertEquals(UnifiedBoardCoordinator.NO_BOARD, coordinator.activeBoard())
    }

    @Test
    fun requestBoard_switchesToDifferentBoard() {
        val host = FakeHost()
        val coordinator = coordinatorWith(host)

        coordinator.requestBoard(CLIPBOARD)
        coordinator.requestBoard(FCC)

        // openBoard is exclusive-open on the host side; switching just opens the new one.
        assertEquals(listOf(CLIPBOARD, FCC), host.opened)
        assertTrue(host.closed.isEmpty())
        assertEquals(FCC, coordinator.activeBoard())
    }

    @Test
    fun requestBoard_ignoresNoBoard() {
        val host = FakeHost()
        val coordinator = coordinatorWith(host)
        coordinator.requestBoard(CLIPBOARD)

        coordinator.requestBoard(UnifiedBoardCoordinator.NO_BOARD)

        assertEquals(listOf(CLIPBOARD), host.opened)
        assertTrue(host.closed.isEmpty())
        assertEquals(CLIPBOARD, coordinator.activeBoard())
    }

    @Test
    fun closeBoard_closesActive() {
        val host = FakeHost()
        val coordinator = coordinatorWith(host)
        coordinator.requestBoard(FCC)

        coordinator.closeBoard()

        assertEquals(listOf(FCC), host.closed)
        assertEquals(UnifiedBoardCoordinator.NO_BOARD, coordinator.activeBoard())
    }

    @Test
    fun closeBoard_noopWhenNoneActive() {
        val host = FakeHost()
        val coordinator = coordinatorWith(host)

        coordinator.closeBoard()

        assertTrue(host.closed.isEmpty())
    }

    @Test
    fun onTextKeyPressed_closesActiveBoard() {
        val host = FakeHost()
        val coordinator = coordinatorWith(host)
        coordinator.requestBoard(EMOJI)

        coordinator.onTextKeyPressed()

        assertEquals(listOf(EMOJI), host.closed)
        assertEquals(UnifiedBoardCoordinator.NO_BOARD, coordinator.activeBoard())
    }

    @Test
    fun onTextKeyPressed_noopWhenNoBoard() {
        val host = FakeHost()
        val coordinator = coordinatorWith(host)

        coordinator.onTextKeyPressed()

        assertTrue(host.closed.isEmpty())
    }

    @Test
    fun notifyBoardClosed_thenKeyPressOpensImmediately() {
        // Typing dismissed the board (reported via notifyBoardClosed / onTextKeyPressed): the next
        // board-key press must open.
        val host = FakeHost()
        val coordinator = coordinatorWith(host)
        coordinator.requestBoard(CLIPBOARD)

        coordinator.notifyBoardClosed()
        coordinator.requestBoard(CLIPBOARD)

        assertEquals(listOf(CLIPBOARD, CLIPBOARD), host.opened)
        assertTrue(host.closed.isEmpty())
        assertEquals(CLIPBOARD, coordinator.activeBoard())
    }

    @Test
    fun notifyBoardOpened_thenKeyPressTogglesClosed() {
        // A board opened outside the coordinator (e.g. touch) is reconciled, so the
        // physical key then closes it rather than reopening.
        val host = FakeHost()
        val coordinator = coordinatorWith(host)

        coordinator.notifyBoardOpened(CLIPBOARD)
        coordinator.requestBoard(CLIPBOARD)

        assertTrue(host.opened.isEmpty())
        assertEquals(listOf(CLIPBOARD), host.closed)
    }

    @Test
    fun isBoardShowing_reflectsActiveBoard() {
        val host = FakeHost()
        val coordinator = coordinatorWith(host)
        coordinator.requestBoard(CLIPBOARD)

        assertTrue(coordinator.isBoardShowing(CLIPBOARD))
        assertFalse(coordinator.isBoardShowing(FCC))
        assertFalse(coordinator.isBoardShowing(UnifiedBoardCoordinator.NO_BOARD))
    }

    @Test
    fun requestBoard_switchSurvivesExclusiveOpenTransient() {
        // Production's openBoard (dispatch) first reports the OLD board closed
        // (exclusive-open bookkeeping) and then the new board opened. The end state
        // must be the new board, and a follow-up press must close it.
        val host = object : UnifiedBoardCoordinator.BoardHost {
            lateinit var coordinator: UnifiedBoardCoordinator
            override fun openBoard(keyCode: Int) {
                coordinator.notifyBoardClosed() // exclusive-open clears first
                coordinator.notifyBoardOpened(keyCode)
            }
            override fun closeBoard(keyCode: Int) {
                coordinator.notifyBoardClosed()
            }
        }
        val coordinator = UnifiedBoardCoordinator(host)
        host.coordinator = coordinator

        coordinator.requestBoard(CLIPBOARD)
        coordinator.requestBoard(FCC)
        assertEquals(FCC, coordinator.activeBoard())

        coordinator.requestBoard(FCC)
        assertEquals(UnifiedBoardCoordinator.NO_BOARD, coordinator.activeBoard())
    }

    @Test
    fun requestBoard_failedOpenLeavesCoordinatorTruthful() {
        // A host whose open never succeeds (e.g. component missing) must not leave
        // the coordinator claiming a board is open.
        val host = object : UnifiedBoardCoordinator.BoardHost {
            override fun openBoard(keyCode: Int) { /* open fails: no report */ }
            override fun closeBoard(keyCode: Int) {}
        }
        val coordinator = UnifiedBoardCoordinator(host)

        coordinator.requestBoard(CLIPBOARD)

        assertEquals(UnifiedBoardCoordinator.NO_BOARD, coordinator.activeBoard())
    }

    private companion object {
        const val CLIPBOARD = -25
        const val FCC = -42
        const val EMOJI = -11
    }
}
