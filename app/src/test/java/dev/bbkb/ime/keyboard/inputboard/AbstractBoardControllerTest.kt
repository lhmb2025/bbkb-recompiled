package dev.bbkb.ime.keyboard.inputboard

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins what [AbstractBoardController] must not do: derive "which board is open" from view state (no
 * `getVisibility()`, no cached showing flag). A side effect can hide the view between a board key's down and up;
 * the base class must ask the subclass every time, or the key-up reopens the board instead of closing it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AbstractBoardControllerTest {

    /** Minimal board: a settable "is my view up" answer and a record of the template calls. */
    private class FakeBoard(
        keyCode: Int = KEY_CODE,
        /** null = the base class's answer. */
        private val enabled: Boolean? = null,
    ) : AbstractBoardController<FakeBoard.Listener>(keyCode, null, Listener()) {

        class Listener

        var viewIsUp = false
        var showCalls = 0
        var hideCalls = 0
        var destroyCalls = 0
        var isShowingQueries = 0
        var view: View? = null

        override fun isShowing(): Boolean {
            isShowingQueries++
            return viewIsUp
        }

        override fun isEnabled(): Boolean = enabled ?: super.isEnabled()
        override fun peekBoardView(): View? = view
        override fun onShow() { showCalls++; viewIsUp = true }
        override fun onHide() { hideCalls++; viewIsUp = false }
        override fun onDestroy() { destroyCalls++ }

        companion object { const val KEY_CODE = -46 }
    }

    @Test
    fun theKeycodeIsTheOneItWasBuiltWith() {
        assertEquals(-46, FakeBoard().keyCode)
        assertEquals(-25, FakeBoard(-25).keyCode)
    }

    @Test
    fun boardsAreEnabledUnlessTheSubclassSaysOtherwise() {
        assertTrue(FakeBoard().isEnabled)
    }

    @Test
    fun showOpensAClosedBoardExactlyOnce() {
        val board = FakeBoard()

        board.show()
        board.show()

        assertEquals("second show must be a no-op, not a re-open", 1, board.showCalls)
        assertTrue(board.isShowing)
    }

    @Test
    fun hideClosesAnOpenBoardExactlyOnce() {
        val board = FakeBoard().apply { show() }

        board.hide()
        board.hide()

        assertEquals("second hide must be a no-op", 1, board.hideCalls)
        assertFalse(board.isShowing)
    }

    @Test
    fun hidingAClosedBoardDoesNothing() {
        val board = FakeBoard()

        board.hide()

        assertEquals(0, board.hideCalls)
    }

    @Test
    fun theBoardIsReopenableAfterHiding() {
        // A board hidden by a side effect must still open on the next request rather than getting stuck.
        val board = FakeBoard()
        board.show()
        board.viewIsUp = false // a side effect hid the view behind the controller's back

        board.show()

        assertEquals(2, board.showCalls)
    }

    // Toggle: the board shortcuts' route with the input menu off.

    @Test
    fun toggleOpensAClosedBoardAndClosesItOnTheNextPress() {
        val board = FakeBoard()

        board.toggle()
        assertTrue(board.isShowing)

        board.toggle()
        assertFalse(board.isShowing)
        assertEquals(1, board.showCalls)
        assertEquals(1, board.hideCalls)
    }

    @Test
    fun toggleLeavesADisabledBoardClosed() {
        val board = FakeBoard(enabled = false)

        board.toggle()

        assertEquals(0, board.showCalls)
        assertFalse(board.isShowing)
    }

    @Test
    fun toggleStillClosesABoardThatHasBecomeDisabled() {
        val board = FakeBoard(enabled = false).apply { viewIsUp = true }

        board.toggle()

        assertEquals(1, board.hideCalls)
        assertFalse(board.isShowing)
    }

    @Test
    fun toggleAsksTheViewEachTimeSoABoardHiddenBehindItsBackReopens() {
        val board = FakeBoard()
        board.toggle()
        board.viewIsUp = false // a side effect hid the view behind the controller's back

        board.toggle()

        assertEquals("the second press must open again, not close a board already gone", 2, board.showCalls)
        assertTrue(board.isShowing)
    }

    @Test
    fun theBaseClassCachesNoShowingState() {
        val board = FakeBoard()
        val before = board.isShowingQueries

        board.show()
        board.hide()
        board.onRefresh()

        assertTrue(
            "the base class must ask the subclass every time rather than remember an answer",
            board.isShowingQueries > before,
        )
    }

    @Test
    fun theBaseClassNeverDerivesShowingFromTheView() {
        // peekBoardView() is only for relayout; reading visibility off it would report a clobbered board
        // closed while open.
        val source = java.io.File("src/main/java/dev/bbkb/ime/keyboard/inputboard/AbstractBoardController.java")
            .takeIf { it.isFile }
            ?: java.io.File("app/src/main/java/dev/bbkb/ime/keyboard/inputboard/AbstractBoardController.java")
        assertTrue("AbstractBoardController.java not found from ${java.io.File(".").canonicalPath}", source.isFile)
        val body = source.readText().lineSequence()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }
            .joinToString("\n")
        assertFalse("AbstractBoardController must not read getVisibility()", "getVisibility" in body)
        assertFalse("AbstractBoardController must not read isShown()", "isShown()" in body)
    }

    @Test
    fun destroyRunsSubclassTeardownAndThenDropsTheSharedReferences() {
        val board = FakeBoard()
        assertTrue(board.listener != null)

        board.destroy()

        assertEquals(1, board.destroyCalls)
        assertTrue("the IME reference must be dropped or the board leaks it", board.ime == null)
        assertTrue("the listener reference must be dropped", board.listener == null)
    }

    @Test
    fun refreshRelayoutsOnlyWhileShowing() {
        val board = FakeBoard()
        // No view: must not throw.
        board.onRefresh()
        board.show()
        board.onRefresh()
    }
}
