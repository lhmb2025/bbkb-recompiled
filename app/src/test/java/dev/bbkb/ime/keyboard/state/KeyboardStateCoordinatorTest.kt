package dev.bbkb.ime.keyboard.state

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Pins [KeyboardStateCoordinator.apply] against a fake [KeyboardStateCoordinator.Axes] that records the
 * mechanism calls in order: for each change, exactly which axes move and in what order. Pure JVM.
 */
class KeyboardStateCoordinatorTest {

    /** Records every mechanism call, in order, and answers the predicates from settable state. */
    private class FakeAxes : KeyboardStateCoordinator.Axes {
        val calls = mutableListOf<String>()

        var uimEnabled = true
        var barShowing = false
        var boardOpen = false
        var arrowBarCanRise = true
        val boardsUp = mutableSetOf<Int>()

        override fun leaveEmojiMode() { calls += "leaveEmojiMode" }
        override fun clearPkbSymbolMode() { calls += "clearPkbSymbolMode" }
        override fun isBoardUp(keyCode: Int) = boardsUp.contains(keyCode)
        override fun anyBoardIsOpen() = boardOpen
        override fun sweepBoardsClosed() { calls += "sweepBoardsClosed" }
        override fun sweepAllBoardsClosed() { calls += "sweepAllBoardsClosed" }
        override fun openBoard(keyCode: Int) { calls += "openBoard($keyCode)" }
        override fun closeBoard(keyCode: Int) { calls += "closeBoard($keyCode)" }
        override fun reportBoardOpened(keyCode: Int) { calls += "reportBoardOpened($keyCode)" }
        override fun reportBoardClosed(keyCode: Int) { calls += "reportBoardClosed($keyCode)" }
        override fun isUimEnabled() = uimEnabled
        override fun isBarShowing() = barShowing
        override fun showUimBar() { calls += "showUimBar" }
        override fun hideUimBar() { calls += "hideUimBar" }
        override fun restoreStripOrUimBar() { calls += "restoreStripOrUimBar" }
        override fun canRaiseArrowBar() = arrowBarCanRise
        override fun prepareForArrowBar() { calls += "prepareForArrowBar" }
        override fun raiseArrowBar() { calls += "raiseArrowBar" }
        override fun lowerArrowBarRestoringStrip(requestShiftUpdate: Boolean) {
            calls += "lowerArrowBarRestoringStrip($requestShiftUpdate)"
        }
        override fun cursorModeOn(forced: Boolean) { calls += "cursorModeOn(forced=$forced)" }
        override fun cursorModeOff() { calls += "cursorModeOff" }
    }

    private lateinit var axes: FakeAxes
    private lateinit var coordinator: KeyboardStateCoordinator

    @Before
    fun setUp() {
        axes = FakeAxes()
        coordinator = KeyboardStateCoordinator(axes)
    }

    private fun assertCalls(vararg expected: String) =
        assertEquals(expected.toList(), axes.calls)

    @Test
    fun aLayoutChangeWithNoBoardOpenSweepsNothing() {
        axes.boardOpen = false

        coordinator.apply(KeyboardTransition.switchLayout(KeyboardTransition.Layout.ALPHABET))

        assertCalls()
    }

    @Test
    fun aLayoutChangeWithABoardOpenSweepsIt() {
        axes.boardOpen = true

        coordinator.apply(KeyboardTransition.switchLayout(KeyboardTransition.Layout.ALPHABET))

        assertCalls("sweepBoardsClosed")
    }

    /**
     * One exempt board up spares the whole sweep: keeps the number pad, FCC and voice open across commits.
     */
    @Test
    fun anExemptBoardBeingUpSparesTheSweep() {
        axes.boardOpen = true
        axes.boardsUp += CrossAxisRules.NUMBER_PAD_KEY_CODE

        coordinator.apply(KeyboardTransition.switchLayout(KeyboardTransition.Layout.ALPHABET))

        assertCalls()
    }

    @Test
    fun aNonExemptBoardBeingUpDoesNotSpareTheSweep() {
        axes.boardOpen = true
        axes.boardsUp += CrossAxisRules.EMOJI_KEY_CODE

        coordinator.apply(KeyboardTransition.switchLayout(KeyboardTransition.Layout.ALPHABET))

        assertCalls("sweepBoardsClosed")
    }

    /** A symbol load leaves the bar alone, on-screen and PKB (Sym key) alike. */
    @Test
    fun aSymbolLayoutNeverRaisesTheBar() {
        axes.boardOpen = true

        coordinator.apply(KeyboardTransition.switchLayout(KeyboardTransition.Layout.SYMBOL))

        assertCalls("sweepBoardsClosed")
    }

    /** Leaving emoji is a board switch: leave the emoji layout mode and report board -11 closed. */
    @Test
    fun theEmojiBoardLoweringLeavesEmojiModeThenReportsItClosed() {
        coordinator.apply(KeyboardTransition.boardLowered(CrossAxisRules.EMOJI_KEY_CODE))

        assertCalls("leaveEmojiMode", "reportBoardClosed(-11)")
    }

    /** Leaving the emoji board returns to ALPHABET and nothing else. */
    @Test
    fun theEmojiBoardLoweringNeverClearsAPkbSymbolMode() {
        coordinator.apply(KeyboardTransition.boardLowered(CrossAxisRules.EMOJI_KEY_CODE))

        assertCalls("leaveEmojiMode", "reportBoardClosed(-11)")
    }

    /** Any other board lowering itself is a board report and nothing else, no layout column. */
    @Test
    fun anyOtherBoardLoweringOnlyReportsTheClose() {
        coordinator.apply(KeyboardTransition.boardLowered(CrossAxisRules.VOICE_KEY_CODE))

        assertCalls("reportBoardClosed(-27)")
    }

    /** A board lowering reports one board closed. It must never sweep (that closes the mic panel). */
    @Test
    fun aBoardLoweringNeverSweepsTheOtherBoards() {
        axes.boardOpen = true
        axes.boardsUp += CrossAxisRules.VOICE_KEY_CODE

        coordinator.apply(KeyboardTransition.boardLowered(CrossAxisRules.EMOJI_KEY_CODE))

        assertCalls("leaveEmojiMode", "reportBoardClosed(-11)")
    }

    /**
     * The emoji open is the `OPEN_BOARD` row in two halves because its mechanism spans the mode change:
     * the prologue clears a PKB symbol mode before the board takes the mode...
     */
    @Test
    fun aBoardOpeningRunsTheOpenPrologue() {
        coordinator.apply(KeyboardTransition.boardOpening(CrossAxisRules.EMOJI_KEY_CODE))

        assertCalls("clearPkbSymbolMode")
    }

    /** ...and the epilogue raises the bar and reports the open, in that order. */
    @Test
    fun aBoardRaisedRaisesTheBarThenReportsTheOpen() {
        coordinator.apply(KeyboardTransition.boardRaised(CrossAxisRules.EMOJI_KEY_CODE))

        assertCalls("showUimBar", "reportBoardOpened(-11)")
    }

    /** The bar raise is conditional exactly as `OPEN_BOARD`'s is. */
    @Test
    fun aBoardRaisedWithTheBarAlreadyUpJustReportsTheOpen() {
        axes.barShowing = true

        coordinator.apply(KeyboardTransition.boardRaised(CrossAxisRules.EMOJI_KEY_CODE))

        assertCalls("reportBoardOpened(-11)")
    }

    @Test
    fun aBoardRaisedWithTheUimDisabledRaisesNoBar() {
        axes.uimEnabled = false

        coordinator.apply(KeyboardTransition.boardRaised(CrossAxisRules.EMOJI_KEY_CODE))

        assertCalls("reportBoardOpened(-11)")
    }

    /** `SWEEP_BOARDS` is the board column of a layout change without the layout change. */
    @Test
    fun theBareSweepClosesTheBoardsWithTheCommitRebuildExemptions() {
        axes.boardOpen = true

        coordinator.apply(KeyboardTransition.sweepBoards())

        assertCalls("sweepBoardsClosed")
    }

    @Test
    fun theBareSweepIsSparedByAnExemptBoard() {
        axes.boardOpen = true
        axes.boardsUp += CrossAxisRules.NUMBER_PAD_KEY_CODE

        coordinator.apply(KeyboardTransition.sweepBoards())

        assertCalls()
    }

    @Test
    fun openingABoardRaisesTheBarFirst() {
        coordinator.apply(KeyboardTransition.openBoard(-37))

        assertCalls("showUimBar", "openBoard(-37)")
    }

    @Test
    fun openingABoardWithTheBarAlreadyUpJustOpensIt() {
        axes.barShowing = true

        coordinator.apply(KeyboardTransition.openBoard(-37))

        assertCalls("openBoard(-37)")
    }

    @Test
    fun closingABoardTouchesOnlyTheBoardAxisHere() {
        coordinator.apply(KeyboardTransition.closeBoard(-37))

        assertCalls("closeBoard(-37)")
    }

    /**
     * The flag goes on before the arrow bar replaces the boards: `FccController` reads it during that
     * teardown.
     */
    @Test
    fun enteringCursorModeTurnsTheModeOnThenRaisesTheArrowBarOverTheBoards() {
        coordinator.apply(KeyboardTransition.enterCursorMode(false))

        assertCalls(
            "cursorModeOn(forced=false)",
            "prepareForArrowBar",
            "sweepAllBoardsClosed",
            "raiseArrowBar",
        )
    }

    /** The board column is a call of its own, separate from the bar work. */
    @Test
    fun enteringCursorModeSweepsEveryBoardWithNoExemptions() {
        axes.boardOpen = true
        axes.boardsUp += CrossAxisRules.NUMBER_PAD_KEY_CODE

        coordinator.apply(KeyboardTransition.enterCursorMode(false))

        assertCalls(
            "cursorModeOn(forced=false)",
            "prepareForArrowBar",
            "sweepAllBoardsClosed",
            "raiseArrowBar",
        )
    }

    /** With the arrow bar already up the mode still goes on, but nothing else moves. */
    @Test
    fun enteringCursorModeWithTheArrowBarAlreadyUpMovesNothingButTheMode() {
        axes.arrowBarCanRise = false
        axes.boardOpen = true

        coordinator.apply(KeyboardTransition.enterCursorMode(false))

        assertCalls("cursorModeOn(forced=false)")
    }

    /** The forced entry reconciles a state already on screen: sweeping would take down the caller's board. */
    @Test
    fun aForcedCursorModeEntryLeavesTheBoardsAndTheBarAlone() {
        coordinator.apply(KeyboardTransition.enterCursorMode(true))

        assertCalls("cursorModeOn(forced=true)")
    }

    @Test
    fun leavingCursorModeTurnsTheModeOffThenLowersTheArrowBar() {
        coordinator.apply(KeyboardTransition.exitCursorMode(false))

        assertCalls("cursorModeOff", "lowerArrowBarRestoringStrip(false)")
    }

    /** The shift-update flag (the physical-backspace exit) is carried through untouched. */
    @Test
    fun theShiftUpdateFlagReachesTheBarMechanism() {
        coordinator.apply(KeyboardTransition.exitCursorMode(true))

        assertCalls("cursorModeOff", "lowerArrowBarRestoringStrip(true)")
    }

    /** Leaving cursor mode opens nothing and closes nothing on the board axis. */
    @Test
    fun leavingCursorModeTouchesNoBoard() {
        axes.boardOpen = true
        axes.boardsUp += CrossAxisRules.EMOJI_KEY_CODE

        coordinator.apply(KeyboardTransition.exitCursorMode(false))

        assertCalls("cursorModeOff", "lowerArrowBarRestoringStrip(false)")
    }

    @Test
    fun showBarRaisesTheBarUnconditionally() {
        axes.barShowing = true
        axes.uimEnabled = false

        coordinator.apply(KeyboardTransition.showBar())

        assertCalls("showUimBar")
    }

    @Test
    fun hideBarLowersTheBarAndNothingElse() {
        axes.boardOpen = true

        coordinator.apply(KeyboardTransition.hideBar())

        assertCalls("hideUimBar")
    }

    /** The bar axis entry point that makes a decision has a row of its own. */
    @Test
    fun restoreBarPutsBackWhicheverOfTheTwoThisEditorGets() {
        axes.boardOpen = true
        axes.boardsUp += CrossAxisRules.EMOJI_KEY_CODE

        coordinator.apply(KeyboardTransition.restoreBar())

        assertCalls("restoreStripOrUimBar")
    }
}
