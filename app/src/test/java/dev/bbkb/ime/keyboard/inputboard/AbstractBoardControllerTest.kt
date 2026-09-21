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
 * §5.6 item 1: the board-controller skeleton the five boards were each re-implementing.
 *
 * The important thing this pins is what the base class must NOT do. `UnifiedBoardCoordinator`
 * exists because "which board is open" used to be derived from clobberable view state, and that
 * regression was proven on-device: a key-down side effect hid the view between a board key's down
 * and up, so the key-up handler saw "nothing open" and reopened the board instead of closing it.
 * The base class must not reintroduce any such derivation — no `getVisibility()`, no cached
 * "showing" flag of its own. It asks the subclass, every time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AbstractBoardControllerTest {

    /** Minimal board: a settable "is my view up" answer and a record of the template calls. */
    private class FakeBoard(
        keyCode: Int = KEY_CODE,
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

    // ── the show/hide guard ──────────────────────────────────────────────────

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
        // The regression UnifiedBoardCoordinator was created for: a board that has been hidden by
        // a side effect must still open on the next request rather than getting stuck.
        val board = FakeBoard()
        board.show()
        board.viewIsUp = false // a side effect hid the view behind the controller's back

        board.show()

        assertEquals(2, board.showCalls)
    }

    // ── no state of its own ──────────────────────────────────────────────────

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
        // peekBoardView() is only ever used to relayout. If the base class ever starts reading
        // visibility off it, a board whose view was clobbered would report closed while open —
        // exactly the on-device bug UnifiedBoardCoordinator was introduced to fix.
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

    // ── teardown ─────────────────────────────────────────────────────────────

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
