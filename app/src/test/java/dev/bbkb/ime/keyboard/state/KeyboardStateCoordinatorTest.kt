package dev.bbkb.ime.keyboard.state

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Pins the funnel — [KeyboardStateCoordinator.apply] — against a fake [KeyboardStateCoordinator.Axes]
 * that records the mechanism calls in order.
 *
 * This is the file that says, for each declarative change, **exactly which axes move and in what
 * order**. The production `Axes` does nothing but forward to the same mechanism methods the
 * scattered sweeps used to call, so what is pinned here is the whole of the decision.
 *
 * Pure JVM — no Robolectric, no Android.
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

    // ═══════════════════════════════════════════════ layout axis

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
     * The exemption, from the table: one exempt board up spares the whole sweep. This is the rule
     * that keeps the number pad, FCC and the voice panel open across every committed character.
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

    /** The bar goes up BEFORE the sweep — the order `setPkbSymbolsKeyboard` has always used. */
    @Test
    fun thePkbSymbolLayoutRaisesTheBarBeforeSweepingTheBoards() {
        axes.boardOpen = true

        coordinator.apply(KeyboardTransition.switchLayout(KeyboardTransition.Layout.PKB_SYMBOL))

        assertCalls("showUimBar", "sweepBoardsClosed")
    }

    @Test
    fun thePkbSymbolLayoutLeavesAnAlreadyRaisedBarAlone() {
        axes.barShowing = true

        coordinator.apply(KeyboardTransition.switchLayout(KeyboardTransition.Layout.PKB_SYMBOL))

        assertCalls()
    }

    @Test
    fun thePkbSymbolLayoutDoesNotRaiseTheBarWithTheUimDisabled() {
        axes.uimEnabled = false

        coordinator.apply(KeyboardTransition.switchLayout(KeyboardTransition.Layout.PKB_SYMBOL))

        assertCalls()
    }

    /**
     * The on-screen symbol keyboard carries its own row of keys, so its load leaves the bar alone —
     * including on a PKB, where `switchToSymbolFromAlphabet` loads it.
     */
    @Test
    fun theOnScreenSymbolLayoutNeverRaisesTheBar() {
        axes.boardOpen = true

        coordinator.apply(KeyboardTransition.switchLayout(KeyboardTransition.Layout.SYMBOL))

        assertCalls("sweepBoardsClosed")
    }

    /**
     * `setKeyboard`'s old `hideEmojiKeyboard()` leak, now an ordinary board switch: leave the emoji
     * mode (the layout half of emoji's ONE identity) and report board -11 closed.
     */
    @Test
    fun theEmojiBoardLoweringLeavesEmojiModeThenReportsItClosed() {
        coordinator.apply(KeyboardTransition.boardLowered(CrossAxisRules.EMOJI_KEY_CODE))

        assertCalls("leaveEmojiMode", "reportBoardClosed(-11)")
    }

    /**
     * **R1:** leaving the emoji board returns to ALPHABET and nothing else. The conditional
     * `clearPkbSymbolMode()` that used to trail the board report is gone — it could never fire
     * (the mode reset ran first) and the rule it described is not wanted.
     */
    @Test
    fun theEmojiBoardLoweringNeverClearsAPkbSymbolMode() {
        coordinator.apply(KeyboardTransition.boardLowered(CrossAxisRules.EMOJI_KEY_CODE))

        assertCalls("leaveEmojiMode", "reportBoardClosed(-11)")
    }

    /** Any OTHER board lowering itself is a board report and nothing else — no layout column. */
    @Test
    fun anyOtherBoardLoweringOnlyReportsTheClose() {
        coordinator.apply(KeyboardTransition.boardLowered(CrossAxisRules.VOICE_KEY_CODE))

        assertCalls("reportBoardClosed(-27)")
    }

    /** A board lowering reports ONE board closed. It must never sweep — that is what broke the mic. */
    @Test
    fun aBoardLoweringNeverSweepsTheOtherBoards() {
        axes.boardOpen = true
        axes.boardsUp += CrossAxisRules.VOICE_KEY_CODE

        coordinator.apply(KeyboardTransition.boardLowered(CrossAxisRules.EMOJI_KEY_CODE))

        assertCalls("leaveEmojiMode", "reportBoardClosed(-11)")
    }

    // ══════════════════════════════════ a board that raises itself (R2)

    /**
     * **R2.** The emoji board's open is the ordinary `OPEN_BOARD` row in two halves, because its
     * mechanism spans the mode change: the prologue clears a PKB symbol mode BEFORE the board takes
     * the mode...
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

    // ══════════════════════════════════════════════ the bare board sweep

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

    // ═══════════════════════════════════════════════ board axis

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

    // ═══════════════════════════════════════════════ cursor axis

    /**
     * Entering: the flag goes on BEFORE the arrow bar replaces the boards. `FccController` reads
     * the flag from inside that teardown, so the order is load-bearing.
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

    /**
     * **R4.** The board column is a call of its own now. Phase 1a could not split it, because
     * `showArrowBar` closed the boards twice and the second close was wrapped around the bar work;
     * the first of those two was the inverted `hideUnifiedInputBoard()`, which did nothing while
     * the UIM was enabled. R4 deleted it and the columns came apart.
     */
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

    /**
     * The arrow bar's own guard spans all three steps: with the bar already up the mode still goes
     * on, but nothing else moves. That is the `showArrowBar()` early return, now asked once.
     */
    @Test
    fun enteringCursorModeWithTheArrowBarAlreadyUpMovesNothingButTheMode() {
        axes.arrowBarCanRise = false
        axes.boardOpen = true

        coordinator.apply(KeyboardTransition.enterCursorMode(false))

        assertCalls("cursorModeOn(forced=false)")
    }

    /**
     * The forced entry is FCC reconciling a state already on screen: the mode goes on and nothing
     * else moves. Sweeping there would take down the board the caller just put up.
     */
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

    // ═══════════════════════════════════════════════════ bar axis

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

    /** The bar axis' one entry point with a decision in it now has a row of its own. */
    @Test
    fun restoreBarPutsBackWhicheverOfTheTwoThisEditorGets() {
        axes.boardOpen = true
        axes.boardsUp += CrossAxisRules.EMOJI_KEY_CODE

        coordinator.apply(KeyboardTransition.restoreBar())

        assertCalls("restoreStripOrUimBar")
    }
}
