package dev.bbkb.ime.keyboard.state

import dev.bbkb.ime.keyboard.state.CrossAxisRules.BarEffect
import dev.bbkb.ime.keyboard.state.CrossAxisRules.BoardEffect
import dev.bbkb.ime.keyboard.state.CrossAxisRules.CursorEffect
import dev.bbkb.ime.keyboard.state.CrossAxisRules.LayoutEffect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the [CrossAxisRules] table row by row: what each transition closes, raises and reports. Pure JVM. */
class CrossAxisRulesTest {

    private fun row(t: KeyboardTransition) = CrossAxisRules.ruleFor(t)

    @Test
    fun switchLayoutSweepsTheBoardsAndLeavesTheOtherAxesAlone() {
        val t = KeyboardTransition.switchLayout(KeyboardTransition.Layout.ALPHABET)

        assertEquals(LayoutEffect.UNCHANGED, row(t).layout())
        assertEquals(BoardEffect.CLOSE_ALL_UNLESS_EXEMPT, row(t).board())
        assertEquals(BarEffect.UNCHANGED, CrossAxisRules.barFor(t))
        assertEquals(CursorEffect.UNCHANGED, row(t).cursor())
    }

    /**
     * No layout load touches the bar, symbol keyboards included: the next suggestion update would take it
     * back, so each page turn would flash it.
     */
    @Test
    fun noLayoutLoadTouchesTheBar() {
        for (layout in KeyboardTransition.Layout.values()) {
            assertEquals(
                "layout $layout must not touch the bar",
                BarEffect.UNCHANGED,
                CrossAxisRules.barFor(KeyboardTransition.switchLayout(layout)),
            )
        }
    }

    /**
     * Emoji has one board identity, so its lowering is an ordinary board switch scoped to -11, never "no
     * board is open".
     */
    @Test
    fun theEmojiBoardLoweringClosesTheEmojiBoardOnly() {
        val t = KeyboardTransition.boardLowered(CrossAxisRules.EMOJI_KEY_CODE)

        assertEquals(LayoutEffect.LEAVE_EMOJI_MODE, CrossAxisRules.layoutFor(t))
        assertEquals(BoardEffect.REPORT_CLOSED, row(t).board())
        assertEquals(BarEffect.UNCHANGED, CrossAxisRules.barFor(t))
        assertEquals(CursorEffect.UNCHANGED, row(t).cursor())
    }

    /**
     * Emoji is the only board whose close is also a layout-mode change, because its identity lives in the
     * layout mode.
     */
    @Test
    fun onlyTheEmojiBoardsLoweringTouchesTheLayoutAxis() {
        assertEquals(
            LayoutEffect.LEAVE_EMOJI_MODE,
            CrossAxisRules.layoutFor(KeyboardTransition.boardLowered(CrossAxisRules.EMOJI_KEY_CODE)),
        )
        for (other in listOf(
            CrossAxisRules.CURSOR_BOARD_KEY_CODE,
            CrossAxisRules.NUMBER_PAD_KEY_CODE,
            CrossAxisRules.VOICE_KEY_CODE,
            CrossAxisRules.CLIPBOARD_KEY_CODE,
        )) {
            assertEquals(
                "board $other lowering must not touch the layout axis",
                LayoutEffect.UNCHANGED,
                CrossAxisRules.layoutFor(KeyboardTransition.boardLowered(other)),
            )
        }
    }

    /**
     * A board that raises itself follows the `OPEN_BOARD` row split in two: a prologue (the layout clear, which
     * must precede the mode change) and an epilogue (the bar raise and the open report).
     */
    @Test
    fun aSelfRaisedBoardFollowsTheOrdinaryOpenRuleInTwoHalves() {
        val opening = KeyboardTransition.boardOpening(CrossAxisRules.EMOJI_KEY_CODE)
        val raised = KeyboardTransition.boardRaised(CrossAxisRules.EMOJI_KEY_CODE)
        val openBoard = KeyboardTransition.openBoard(CrossAxisRules.EMOJI_KEY_CODE)

        assertEquals(row(openBoard).layout(), CrossAxisRules.layoutFor(opening))
        assertEquals(BarEffect.UNCHANGED, CrossAxisRules.barFor(opening))
        assertEquals(BoardEffect.UNCHANGED, row(opening).board())

        assertEquals(LayoutEffect.UNCHANGED, CrossAxisRules.layoutFor(raised))
        assertEquals(CrossAxisRules.barFor(openBoard), CrossAxisRules.barFor(raised))
        assertEquals(BoardEffect.REPORT_OPENED, row(raised).board())
    }

    /** The bare sweep is the board column of a layout change without the layout change. */
    @Test
    fun theBareSweepIsTheBoardColumnAlone() {
        val t = KeyboardTransition.sweepBoards()

        assertEquals(LayoutEffect.UNCHANGED, CrossAxisRules.layoutFor(t))
        assertEquals(BoardEffect.CLOSE_ALL_UNLESS_EXEMPT, row(t).board())
        assertEquals(BarEffect.UNCHANGED, CrossAxisRules.barFor(t))
        assertEquals(CursorEffect.UNCHANGED, row(t).cursor())
    }

    @Test
    fun openingABoardClearsPkbSymbolModeAndRaisesTheBar() {
        val t = KeyboardTransition.openBoard(CrossAxisRules.NUMBER_PAD_KEY_CODE)

        assertEquals(LayoutEffect.CLEAR_PKB_SYMBOL_MODE, row(t).layout())
        assertEquals(BoardEffect.OPEN_EXCLUSIVE, row(t).board())
        assertEquals(BarEffect.SHOW_UIM_IF_HIDDEN, CrossAxisRules.barFor(t))
        assertEquals(CursorEffect.UNCHANGED, row(t).cursor())
    }

    /**
     * No board's close touches the bar here; what the bar goes back to is `AuxBarChoice`'s, tested where
     * the funnel schedules it.
     */
    @Test
    fun noBoardsCloseTouchesTheBar() {
        for (other in listOf(
            CrossAxisRules.CURSOR_BOARD_KEY_CODE,
            CrossAxisRules.NUMBER_PAD_KEY_CODE,
            CrossAxisRules.VOICE_KEY_CODE,
            CrossAxisRules.EMOJI_KEY_CODE,
        )) {
            assertEquals(
                "closing board $other must not touch the bar",
                BarEffect.UNCHANGED,
                CrossAxisRules.barFor(KeyboardTransition.closeBoard(other)),
            )
        }
    }

    /** Hide the boards and the suggestion views, raise the arrows. */
    @Test
    fun enteringCursorModeTakesEveryBoardDownIncludingTheExemptOnes() {
        val t = KeyboardTransition.enterCursorMode(false)

        assertEquals(LayoutEffect.UNCHANGED, row(t).layout())
        assertEquals(
            "cursor mode replaces the boards outright — the layout-change exemptions do not apply",
            BoardEffect.CLOSE_ALL,
            row(t).board(),
        )
        assertEquals(BarEffect.SHOW_ARROW_BAR, CrossAxisRules.barFor(t))
        assertEquals(CursorEffect.ON, row(t).cursor())
    }

    /** Leaving cursor mode opens no boards and closes none. */
    @Test
    fun leavingCursorModeOnlyTouchesTheBarAndTheCursor() {
        val t = KeyboardTransition.exitCursorMode(false)

        assertEquals(LayoutEffect.UNCHANGED, row(t).layout())
        assertEquals(BoardEffect.UNCHANGED, row(t).board())
        assertEquals(BarEffect.HIDE_ARROW_BAR, CrossAxisRules.barFor(t))
        assertEquals(CursorEffect.OFF, row(t).cursor())
    }

    @Test
    fun theBarTransitionsTouchNothingElse() {
        for (t in listOf(KeyboardTransition.showBar(), KeyboardTransition.hideBar())) {
            assertEquals(LayoutEffect.UNCHANGED, row(t).layout())
            assertEquals(BoardEffect.UNCHANGED, row(t).board())
            assertEquals(CursorEffect.UNCHANGED, row(t).cursor())
        }
        assertEquals(BarEffect.SHOW_UIM, CrossAxisRules.barFor(KeyboardTransition.showBar()))
        assertEquals(BarEffect.HIDE_UIM, CrossAxisRules.barFor(KeyboardTransition.hideBar()))
    }

    /** Every kind has a row: a new transition cannot be added without deciding its four columns. */
    @Test
    fun everyTransitionKindHasARow() {
        val samples = listOf(
            KeyboardTransition.switchLayout(KeyboardTransition.Layout.ALPHABET),
            KeyboardTransition.sweepBoards(),
            KeyboardTransition.openBoard(-11),
            KeyboardTransition.closeBoard(-11),
            KeyboardTransition.boardOpening(-11),
            KeyboardTransition.boardRaised(-11),
            KeyboardTransition.boardLowered(-11),
            KeyboardTransition.enterCursorMode(false),
            KeyboardTransition.exitCursorMode(false),
            KeyboardTransition.showBar(),
            KeyboardTransition.hideBar(),
            KeyboardTransition.restoreBar(),
        )
        assertEquals(
            "one sample per kind",
            KeyboardTransition.Kind.values().size,
            samples.map { it.kind() }.toSet().size,
        )
        samples.forEach { CrossAxisRules.ruleFor(it) }
    }

    /**
     * FCC and the number pad are boards the user types from; voice is dictated from and reaches the sweep
     * through the result commit.
     */
    @Test
    fun theExemptSetIsFccTheNumberPadAndVoiceInThatOrder() {
        assertArrayEquals(
            intArrayOf(-42, -46, -27),
            CrossAxisRules.boardsExemptFromCommitRebuild(),
        )
    }

    @Test
    fun theExemptSetCannotBeRewrittenThroughTheAccessor() {
        CrossAxisRules.boardsExemptFromCommitRebuild()[0] = 999

        assertArrayEquals(intArrayOf(-42, -46, -27), CrossAxisRules.boardsExemptFromCommitRebuild())
    }

    @Test
    fun onlyTheThreeExemptBoardsSurviveALayoutChange() {
        assertTrue(CrossAxisRules.boardSurvivesCommitRebuild(-42))
        assertTrue(CrossAxisRules.boardSurvivesCommitRebuild(-46))
        assertTrue(CrossAxisRules.boardSurvivesCommitRebuild(-27))
        assertFalse("emoji", CrossAxisRules.boardSurvivesCommitRebuild(-11))
        assertFalse("clipboard", CrossAxisRules.boardSurvivesCommitRebuild(-37))
        assertFalse("no board", CrossAxisRules.boardSurvivesCommitRebuild(0))
    }

    /**
     * The text-key policy has its own exempt set: the number pad joins FCC, because typing a letter must
     * not close a board typed from.
     */
    @Test
    fun theTextKeyExemptSetIsFccAndTheNumberPad() {
        assertArrayEquals(intArrayOf(-42, -46), CrossAxisRules.boardsExemptFromTextKey())
    }

    @Test
    fun theTextKeyExemptSetCannotBeRewrittenThroughTheAccessor() {
        CrossAxisRules.boardsExemptFromTextKey()[0] = 999

        assertArrayEquals(intArrayOf(-42, -46), CrossAxisRules.boardsExemptFromTextKey())
    }

    /**
     * Voice differs deliberately: a dictation commit leaves the panel up, but typing a letter means the
     * user stopped dictating.
     */
    @Test
    fun voiceSurvivesACommitRebuildButNotATextKey() {
        assertTrue(CrossAxisRules.boardSurvivesCommitRebuild(-27))
        assertFalse(CrossAxisRules.boardSurvivesTextKey(-27))
    }

    @Test
    fun onlyFccAndTheNumberPadSurviveATextKey() {
        assertTrue(CrossAxisRules.boardSurvivesTextKey(-42))
        assertTrue(CrossAxisRules.boardSurvivesTextKey(-46))
        assertFalse("emoji", CrossAxisRules.boardSurvivesTextKey(-11))
        assertFalse("clipboard", CrossAxisRules.boardSurvivesTextKey(-25))
        assertFalse("no board", CrossAxisRules.boardSurvivesTextKey(0))
    }

    // A text key during emoji search is handled by the emoji board before dismissal runs
    // (EmojiQueryKeyRoutingTest).

    /**
     * Layout values are board keycodes: "which keyboard is loaded" and "which board is up" share one
     * vocabulary.
     */
    @Test
    fun everyLayoutValueNamesABoard() {
        assertEquals(-3, CrossAxisRules.boardKeyCodeFor(KeyboardTransition.Layout.ALPHABET))
        assertEquals(-22, CrossAxisRules.boardKeyCodeFor(KeyboardTransition.Layout.SYMBOL))
        assertEquals(-11, CrossAxisRules.boardKeyCodeFor(KeyboardTransition.Layout.EMOJI))
        assertEquals(-23, CrossAxisRules.boardKeyCodeFor(KeyboardTransition.Layout.MENU))
    }

    /** "Whatever is loaded" names no board: it is the absence of a layout change. */
    @Test
    fun theUnchangedLayoutNamesNoBoard() {
        assertEquals(
            KeyboardTransition.NO_BOARD,
            CrossAxisRules.boardKeyCodeFor(KeyboardTransition.Layout.UNCHANGED),
        )
    }

    /**
     * One exempt board being up spares the whole sweep, which keeps a clipboard board open behind an open
     * number pad.
     */
    @Test
    fun oneExemptBoardUpSparesTheWholeSweep() {
        assertTrue(CrossAxisRules.anyExemptBoardIsUp { it == -46 })
        assertEquals(-46, CrossAxisRules.firstExemptBoardUp { it == -46 })
    }

    @Test
    fun aNonExemptBoardBeingUpDoesNotSpareTheSweep() {
        assertFalse(CrossAxisRules.anyExemptBoardIsUp { it == -11 })
        assertEquals(
            KeyboardTransition.NO_BOARD,
            CrossAxisRules.firstExemptBoardUp { it == -11 },
        )
    }

    /** The probe order decides which exempt board is named in the log, so it is pinned. */
    @Test
    fun theFirstExemptBoardUpIsReportedInProbeOrder() {
        assertEquals(-42, CrossAxisRules.firstExemptBoardUp { it == -42 || it == -27 })
        assertEquals(-46, CrossAxisRules.firstExemptBoardUp { it == -46 || it == -27 })
    }

    @Test
    fun nothingUpMeansNoExemption() {
        assertFalse(CrossAxisRules.anyExemptBoardIsUp { false })
    }
}
