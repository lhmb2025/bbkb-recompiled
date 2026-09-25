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

/**
 * Pins **the table** — [CrossAxisRules] — row by row.
 *
 * The table is the whole point of Phase 1a: before it, "what closes what" was a set of
 * `hide*` / `reset*` sweeps scattered across `KeyboardSwitcher`, `UnifiedInputBoardManager` and
 * `BlackBerryIME`, each carrying its own copy of the rule. Every row below is a rule that used to
 * live in one of those sweeps; the comment on each says which.
 *
 * Pure JVM — no Robolectric, no Android.
 */
class CrossAxisRulesTest {

    private fun row(t: KeyboardTransition) = CrossAxisRules.ruleFor(t)

    // ═══════════════════════════════════════════════════ 1. the rows

    /**
     * Was: the four `unifiedInputBoardManager.hideKeyboardOnKeyboardStateChange()` calls in
     * `KeyboardSwitcher.setAlphabetKeyboard` / `setVkbSymbolsKeyboard` / `setPkbSymbolsKeyboard` /
     * `onSymbolShiftToggle`.
     */
    @Test
    fun switchLayoutSweepsTheBoardsAndLeavesTheOtherAxesAlone() {
        val t = KeyboardTransition.switchLayout(KeyboardTransition.Layout.ALPHABET)

        assertEquals(LayoutEffect.UNCHANGED, row(t).layout())
        assertEquals(BoardEffect.CLOSE_ALL_UNLESS_EXEMPT, row(t).board())
        assertEquals(BarEffect.UNCHANGED, CrossAxisRules.barFor(t))
        assertEquals(CursorEffect.UNCHANGED, row(t).cursor())
    }

    /**
     * Was: the `if (isUimEnabled()) { if (!uim.isShowing()) { uim.show(false); uim.showEmojiBoard() } }`
     * prologue of `KeyboardSwitcher.setPkbSymbolsKeyboard`. The symbol layout is the ONE layout
     * that raises the bar.
     */
    @Test
    fun onlyTheSymbolLayoutRaisesTheBar() {
        assertEquals(
            BarEffect.SHOW_UIM_IF_HIDDEN,
            CrossAxisRules.barFor(
                KeyboardTransition.switchLayout(KeyboardTransition.Layout.PKB_SYMBOL),
            ),
        )
        for (layout in listOf(
            KeyboardTransition.Layout.ALPHABET,
            // The on-screen symbol keyboard carries its own keys — even on a PKB, which loads it
            // through switchToSymbolFromAlphabet — so it leaves the bar where it was.
            KeyboardTransition.Layout.SYMBOL,
            KeyboardTransition.Layout.MENU,
            KeyboardTransition.Layout.UNCHANGED,
        )) {
            assertEquals(
                "layout $layout must not touch the bar",
                BarEffect.UNCHANGED,
                CrossAxisRules.barFor(KeyboardTransition.switchLayout(layout)),
            )
        }
    }

    /**
     * Was: `KeyboardSwitcher.setKeyboard() -> hideEmojiKeyboard()`, the cross-axis leak the plan
     * notes name as the source of every 2026-09-20 symbol/mic bug; then Phase 1a's `LAYOUT_LOADED`
     * row. Phase 1d gave the emoji board ONE identity, so it is an ordinary board switch: the
     * report is scoped to -11, never "no board is open at all".
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
     * Emoji is the ONLY board whose close is also a layout-mode change, because the layout mode is
     * where emoji's identity lives. Every other board lowering is a board report and nothing else.
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
     * R2: a board that raises ITSELF follows the same `OPEN_BOARD` rule as one the funnel opens,
     * split into a prologue (the layout clear, which must precede the mode change) and an epilogue
     * (the bar raise and the open report). Put back together they are the `OPEN_BOARD` row.
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

    /** Was: `UnifiedInputBoardManager.beginBoardTransition() -> KeyboardSwitcher.clearPkbSymbolMode()`. */
    @Test
    fun openingABoardClearsPkbSymbolModeAndRaisesTheBar() {
        val t = KeyboardTransition.openBoard(CrossAxisRules.NUMBER_PAD_KEY_CODE)

        assertEquals(LayoutEffect.CLEAR_PKB_SYMBOL_MODE, row(t).layout())
        assertEquals(BoardEffect.OPEN_EXCLUSIVE, row(t).board())
        assertEquals(BarEffect.SHOW_UIM_IF_HIDDEN, CrossAxisRules.barFor(t))
        assertEquals(CursorEffect.UNCHANGED, row(t).cursor())
    }

    /**
     * Was: the `if (keyCode == -42 && wasOpen && isOnScreenKeyboardVisible())` tail of
     * `UnifiedInputBoardManager.closeBoard`. Exactly one board's close touches the bar.
     */
    @Test
    fun onlyTheCursorBoardsCloseRestoresTheStrip() {
        assertEquals(
            BarEffect.RESTORE_STRIP,
            CrossAxisRules.barFor(KeyboardTransition.closeBoard(CrossAxisRules.CURSOR_BOARD_KEY_CODE)),
        )
        for (other in listOf(
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

    /** Was: `BlackBerryIME.showArrowBar()` — hide the boards and the suggestion views, raise the arrows. */
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

    /** Was: `BlackBerryIME.hideArrowBar()`. Leaving cursor mode opens no boards and closes none. */
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

    // ═══════════════════════════════════════════ 2. the exempt set

    /**
     * The exemption list and its probe order, moved out of
     * `UnifiedInputBoardManager.BOARDS_EXEMPT_FROM_KEYBOARD_STATE_CHANGE`. FCC and the number pad
     * are boards the user TYPES from; voice is the board the user DICTATES from, and it reaches
     * the sweep by the same route — the commit of the result.
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

    // ══════════════════════════ the OTHER key-press policy: a physical text key

    /**
     * **R3(b).** The two "a key was pressed" policies are different rows with different exempt
     * sets. Phase 1a found them disagreeing and recorded it; the owner's ruling settles it: the
     * number pad joins FCC here, because typing a letter must not close a board typed FROM.
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
     * Voice is the one board the two policies disagree about, and deliberately so: a commit of a
     * dictation result must leave the panel up, while typing a letter means the user has stopped
     * dictating.
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

    /** **R3(c).** Emoji dynamic search overrides the exempt set: nothing closes at all. */
    @Test
    fun emojiDynamicSearchStopsATextKeyClosingAnything() {
        assertTrue(CrossAxisRules.textKeyClosesNoBoard(true))
        assertFalse(CrossAxisRules.textKeyClosesNoBoard(false))
    }

    // ══════════════════════════════════════════ every keyboard is a board

    /**
     * Phase 1d's whole point: the layout values are board keycodes. "Which keyboard is loaded" and
     * "which board is up" are the same question, so they use the same vocabulary.
     */
    @Test
    fun everyLayoutValueNamesABoard() {
        assertEquals(-3, CrossAxisRules.boardKeyCodeFor(KeyboardTransition.Layout.ALPHABET))
        assertEquals(-22, CrossAxisRules.boardKeyCodeFor(KeyboardTransition.Layout.SYMBOL))
        assertEquals(-22, CrossAxisRules.boardKeyCodeFor(KeyboardTransition.Layout.PKB_SYMBOL))
        assertEquals(-11, CrossAxisRules.boardKeyCodeFor(KeyboardTransition.Layout.EMOJI))
        assertEquals(-23, CrossAxisRules.boardKeyCodeFor(KeyboardTransition.Layout.MENU))
    }

    /** "Whatever is loaded" names no board — it is the absence of a layout change, not one. */
    @Test
    fun theUnchangedLayoutNamesNoBoard() {
        assertEquals(
            KeyboardTransition.NO_BOARD,
            CrossAxisRules.boardKeyCodeFor(KeyboardTransition.Layout.UNCHANGED),
        )
    }

    /**
     * The on-screen and PKB symbol keyboards are ONE board with two bar rules, which is why the
     * layout enum still has two values for them.
     */
    @Test
    fun theTwoSymbolLayoutsAreOneBoardWithTwoBarRules() {
        assertEquals(
            CrossAxisRules.boardKeyCodeFor(KeyboardTransition.Layout.SYMBOL),
            CrossAxisRules.boardKeyCodeFor(KeyboardTransition.Layout.PKB_SYMBOL),
        )
        assertEquals(
            BarEffect.UNCHANGED,
            CrossAxisRules.barFor(KeyboardTransition.switchLayout(KeyboardTransition.Layout.SYMBOL)),
        )
        assertEquals(
            BarEffect.SHOW_UIM_IF_HIDDEN,
            CrossAxisRules.barFor(
                KeyboardTransition.switchLayout(KeyboardTransition.Layout.PKB_SYMBOL)),
        )
    }

    /**
     * The semantics that make the exemption an exemption: ONE exempt board being up spares the
     * WHOLE sweep, not just itself. That is the long-standing FCC behaviour and is what keeps a
     * clipboard board open behind an open number pad.
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
