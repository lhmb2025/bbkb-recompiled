package dev.bbkb.ime.core.textinput

import android.view.KeyEvent
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.harness.PipelineHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * CHARACTERISATION tests for cursor movement — `CursorController`'s four public moves, and
 * `InputLogic.onUpdateSelection`, which is where a cursor move decides whether the composing
 * session survives.
 *
 * The two halves are the same feature seen from both ends: `CursorController` is the IME
 * *asking* for a move, `onUpdateSelection` is the editor *reporting* one (whoever caused it).
 * Both are asserted through the editor — the calls it received, and whether the composing
 * region and tracker survived.
 *
 * ## `CursorController.moveCursorLeft/Right`
 * ```
 * allowHorizontalCursorBeyondField || select -> one DPAD key event per step (+SHIFT to select)
 * otherwise, allowBatchedCursorMove          -> ONE setSelection to the destination
 * otherwise                                  -> one setSelection PER intermediate position
 * ```
 * `moveCursorUp/Down` have no such split: they are always DPAD key events.
 *
 * ## `InputLogic.onUpdateSelection`
 * ```
 * the RIC already agrees with the reported selection  -> return false, do nothing
 * a selection is involved, OR suggestions are off, OR
 *   the tracker cannot absorb the delta               -> resetComposingAndSelect (session ends)
 * otherwise                                           -> the tracker follows the cursor
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class CursorMovementTest {

    private lateinit var h: PipelineHarness
    private lateinit var subtypeStatic: MockedStatic<SubtypeManager>
    private lateinit var settingsStatic: MockedStatic<SettingsManager>

    @Before
    fun setUp() {
        h = PipelineHarness()

        subtypeStatic = mockStatic(SubtypeManager::class.java)
        val subtypes = mock(SubtypeManager::class.java)
        `when`(subtypes.currentSubtypeLocale).thenReturn(Locale.US)
        subtypeStatic.`when`<SubtypeManager> { SubtypeManager.getInstance() }.thenReturn(subtypes)

        // SEAM: resetComposingAndSelect -> updateDynamicLearningState reads the process-wide
        // SettingsManager singleton rather than the SettingsValues it was passed.
        val sm = mock(SettingsManager::class.java)
        `when`(sm.getSettingsValues()).thenReturn(h.settings)
        settingsStatic = mockStatic(SettingsManager::class.java)
        settingsStatic.`when`<SettingsManager> { SettingsManager.getInstance() }.thenReturn(sm)
    }

    @After
    fun tearDown() {
        settingsStatic.close()
        subtypeStatic.close()
        h.close()
    }

    /** Editor calls made since the last [clearLog], in order. */
    private fun log(): List<String> = h.editor.callLog.toList()
    private fun clearLog() {
        h.editor.callLog.clear()
        h.editor.sentKeyEvents.clear()
    }

    private fun arm(text: String, cursor: Int) {
        h.startSession(text, cursor)
        clearLog()
    }

    /** Arm a live composing region in the editor AND the tracker, as typing would. */
    private fun armComposing(prefix: String, word: String) {
        h.startSession(prefix, prefix.length)
        h.inputLogic.mRichInputConnection.setComposingText(word, 1)
        h.seedComposing(word)
        h.editor.dropPendingSelectionEvents()
        clearLog()
    }

    // ── CursorController: the batched form ──────────────────────────────────────

    @Test
    fun moveCursorRight_batched_isASingleSetSelectionToTheDestination() {
        val settings = h.settingsWith("allowBatchedCursorMove" to true)
        arm("hello world", 0)
        h.inputLogic.moveCursorRight(settings, 5, false)
        assertEquals(listOf("setSelection(5,5)"), log())
        assertEquals(5, h.editor.selStart)
    }

    @Test
    fun moveCursorLeft_batched_isASingleSetSelectionToTheDestination() {
        val settings = h.settingsWith("allowBatchedCursorMove" to true)
        arm("hello world", 11)
        h.inputLogic.moveCursorLeft(settings, 5, false)
        assertEquals(listOf("setSelection(6,6)"), log())
    }

    // ── CursorController: the stepped form ──────────────────────────────────────

    @Test
    fun moveCursorRight_unbatched_stepsOnePositionAtATime() {
        // The stepping IS the `allowBatchedCursorMove == false` setting; collapsing it to one
        // setSelection would make the toggle a no-op, which is why every intermediate position
        // is asserted rather than just the destination.
        arm("hello world", 0)
        h.inputLogic.moveCursorRight(h.settings, 3, false)
        assertEquals(
            listOf("setSelection(1,1)", "setSelection(2,2)", "setSelection(3,3)"),
            log(),
        )
    }

    @Test
    fun moveCursorLeft_unbatched_stepsOnePositionAtATime() {
        arm("hello world", 5)
        h.inputLogic.moveCursorLeft(h.settings, 3, false)
        assertEquals(
            listOf("setSelection(4,4)", "setSelection(3,3)", "setSelection(2,2)"),
            log(),
        )
    }

    @Test
    fun moveCursorLeft_atTheStartOfTheField_clampsAtZero() {
        arm("hello", 1)
        h.inputLogic.moveCursorLeft(h.settings, 5, false)
        assertEquals(listOf("setSelection(0,0)"), log())
    }

    // ── CursorController: the key-event form ────────────────────────────────────

    @Test
    fun moveCursorRight_whenAllowedBeyondTheField_sendsOneDpadPairPerStep() {
        val settings = h.settingsWith("allowHorizontalCursorBeyondField" to true)
        arm("hello", 0)
        h.inputLogic.moveCursorRight(settings, 2, false)
        assertEquals(
            List(4) { KeyEvent.KEYCODE_DPAD_RIGHT },
            h.editor.sentKeyEvents.map { it.keyCode },
        )
        assertTrue("no setSelection on the key-event path", log().none { it.startsWith("setSelection") })
    }

    @Test
    fun moveCursorRight_withSelect_alwaysUsesShiftedKeyEvents() {
        // `select` forces the key-event form even with allowHorizontalCursorBeyondField off,
        // because a setSelection cannot express "extend the selection".
        arm("hello", 0)
        h.inputLogic.moveCursorRight(h.settings, 1, true)
        assertEquals(
            listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT),
            h.editor.sentKeyEvents.map { it.keyCode },
        )
        assertTrue(
            "the shift meta state is what makes it a selection",
            h.editor.sentKeyEvents.all { it.metaState and KeyEvent.META_SHIFT_ON != 0 },
        )
    }

    @Test
    fun moveCursorUpAndDown_areAlwaysKeyEvents() {
        arm("a\nb\nc", 4)
        h.inputLogic.moveCursorUp(1, false)
        h.inputLogic.moveCursorDown(2, false)
        assertEquals(
            listOf(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
            ),
            h.editor.sentKeyEvents.map { it.keyCode },
        )
    }

    // ── CursorController: RTL flips left and right ──────────────────────────────

    @Test
    fun inARightToLeftLayout_moveCursorRightWalksBackwards() {
        // `moveCursorRight` passes `!isRightToLeft()` as the "forward" flag, so in RTL the
        // logical right key moves the cursor toward index 0.
        `when`(h.ime.isCurrentLanguageRtl()).thenReturn(true)
        val settings = h.settingsWith("allowBatchedCursorMove" to true)
        arm("hello", 5)
        h.inputLogic.moveCursorRight(settings, 2, false)
        assertEquals(listOf("setSelection(3,3)"), log())
    }

    @Test
    fun inARightToLeftLayout_moveCursorLeftWalksForwards() {
        `when`(h.ime.isCurrentLanguageRtl()).thenReturn(true)
        val settings = h.settingsWith("allowBatchedCursorMove" to true)
        arm("hello", 0)
        h.inputLogic.moveCursorLeft(settings, 2, false)
        assertEquals(listOf("setSelection(2,2)"), log())
    }

    // ── CursorController: composing is cancelled before every move ──────────────

    @Test
    fun anyCursorMove_cancelsTheComposingSessionFirst() {
        val settings = h.settingsWith("allowBatchedCursorMove" to true)
        armComposing("", "hello")
        assertTrue(h.inputLogic.mComposingTracker.isComposing)

        h.inputLogic.moveCursorLeft(settings, 1, false)

        assertFalse(
            "a deliberate cursor move must end the composing session",
            h.inputLogic.mComposingTracker.isComposing,
        )
        assertFalse(h.editor.hasComposingRegion())
        assertEquals(
            "the region is finished BEFORE the cursor is moved",
            listOf("finishComposingText()", "setSelection(4,4)"), log(),
        )
        assertEquals("the text itself survives", "hello", h.editor.text)
    }

    @Test
    fun moveCursorOverAnEmoji_stepsTheWholeCodePoint() {
        // The grapheme scan: one "left" over a surrogate pair lands before it, not between its
        // two chars.
        val grin = "😀"
        val settings = h.settingsWith("allowBatchedCursorMove" to true)
        arm("a$grin", 1 + grin.length)
        h.inputLogic.moveCursorLeft(settings, 1, false)
        assertEquals(listOf("setSelection(1,1)"), log())
    }

    // ── onUpdateSelection: the no-op case ───────────────────────────────────────

    @Test
    fun selectionChangeThatTheConnectionAlreadyKnowsAbout_isIgnored() {
        armComposing("", "hello")
        // The RIC's cursor is already 5; a report of 5 tells it nothing new.
        assertFalse(h.selectionChanged(oldStart = 5, oldEnd = 5, newStart = 5, newEnd = 5))
        assertTrue(
            "an already-known position must not tear down the composing session",
            h.inputLogic.mComposingTracker.isComposing,
        )
    }

    @Test
    fun selectionChangeWithNoCursorMove_butDifferentOldValues_isStillIgnored() {
        // `isSelectionUnchanged` compares against the RIC's own cursor, not against the "old"
        // values the framework reported — a redundant report from a stale old position is
        // still a no-op.
        armComposing("", "hello")
        assertFalse(h.selectionChanged(oldStart = 3, oldEnd = 3, newStart = 5, newEnd = 5))
        assertTrue(h.inputLogic.mComposingTracker.isComposing)
    }

    // ── Hole: the field-clear after a hardware Enter was ignored (2026-09-20) ────

    @Test
    fun enterHandoff_appClearsTheField_isSeenAsAChange() {
        // The post-Enter state: word finished, connection invalidated (cursor unknown), tracker
        // cleared. The app sends and empties the field, reporting [5,5] -> [0,0]. With the
        // cursor unknown, isSelectionUnchanged used to call that "unchanged" and the IME kept
        // believing the editor held "Hello".
        h.startSession("Hello", 5)
        h.inputLogic.mRichInputConnection.setComposingText("Hello", 1)
        h.inputLogic.mRichInputConnection.finishComposingText()
        h.inputLogic.mRichInputConnection.invalidateConnection()
        h.inputLogic.mComposingTracker.clearAll()
        h.editor.dropPendingSelectionEvents()
        h.editor.appSetText("", 0, 0)

        assertEquals(true, h.deliverSelectionEvent())

        assertEquals(0, h.inputLogic.mRichInputConnection.cursorStart)
        assertFalse(h.inputLogic.mComposingTracker.isComposing)
        assertEquals("", h.editor.getText())
    }

    @Test
    fun enterHandoff_redundantSelectionReport_isStillANoOp() {
        // The report the framework sends for finishComposingText itself, [5,5] -> [5,5], says
        // nothing moved: with the cursor unknown it must stay a no-op, or every Enter would
        // take the reset path for nothing.
        h.startSession("Hello", 5)
        h.inputLogic.mRichInputConnection.setComposingText("Hello", 1)
        h.inputLogic.mRichInputConnection.finishComposingText()
        h.inputLogic.mRichInputConnection.invalidateConnection()
        h.inputLogic.mComposingTracker.clearAll()

        assertFalse(h.selectionChanged(5, 5, 5, 5))
        assertEquals(-1, h.inputLogic.mRichInputConnection.cursorStart)
    }

    // ── onUpdateSelection: the app emptied the field under a composing word ─────

    @Test
    fun appClearingTheFieldUnderASingleComposingWord_endsTheSession() {
        // Messages' Send button: the app reads "Correct" (still composing), sends it and sets
        // the text to "". The selection jump it reports, [7,7] -> [0,0], is the same one a
        // caret placed at the start of the word would report, and moveCursorByCharCount(-7)
        // would absorb it; the editor being empty is what tells the two apart.
        val settings = h.settingsWith("shouldShowLxxButton" to true)
        armComposing("", "Correct")
        h.editor.appSetText("", 0, 0)
        val e = requireNotNull(h.editor.pollSelectionEvent())

        assertTrue(h.selectionChanged(e.oldSelStart, e.oldSelEnd, e.newSelStart, e.newSelEnd, settings))

        assertFalse(
            "the sent word must not survive as a composing session with its cursor at 0",
            h.inputLogic.mComposingTracker.isComposing,
        )
        assertFalse(h.inputLogic.mComposingTracker.isCursorMoved)
        assertEquals("", h.editor.getText())
        assertEquals(0, h.inputLogic.mRichInputConnection.cursorStart)
    }

    @Test
    fun caretPlacedAtTheStartOfTheComposingWord_keepsTheSession() {
        // The counterpart the clear must not be confused with: the same [7,7] -> [0,0] report,
        // but the text is intact — the user put the caret in front of the word, and the next
        // key belongs in front of it.
        val settings = h.settingsWith("shouldShowLxxButton" to true)
        armComposing("", "Correct")
        h.editor.appSetSelection(0, 0)
        h.editor.dropPendingSelectionEvents()

        assertTrue(h.selectionChanged(7, 7, 0, 0, settings))

        assertTrue(h.inputLogic.mComposingTracker.isComposing)
        assertTrue(h.inputLogic.mComposingTracker.isCursorMoved)
        assertEquals("Correct", h.editor.getText())
    }

    @Test
    fun appClearingTheFieldUnderALaterComposingWord_alreadyEndedTheSession() {
        // "Hi there" with "there" composing: the jump [8,8] -> [0,0] never matched the
        // composing length, so the FAILED-move path reset the session before this change.
        // Pinned so the new guard is known to be the single-word case's fix, not a rewrite.
        val settings = h.settingsWith("shouldShowLxxButton" to true)
        armComposing("Hi ", "there")
        h.editor.appSetText("", 0, 0)
        val e = requireNotNull(h.editor.pollSelectionEvent())

        assertTrue(h.selectionChanged(e.oldSelStart, e.oldSelEnd, e.newSelStart, e.newSelEnd, settings))

        assertFalse(h.inputLogic.mComposingTracker.isComposing)
        assertEquals("", h.editor.getText())
    }

    @Test
    fun anUnreachableEditor_isNotTreatedAsEmpty() {
        // A dead connection answers null; that is "unknown", never "empty" — the session is
        // then judged by the cursor arithmetic exactly as before.
        val settings = h.settingsWith("shouldShowLxxButton" to true)
        armComposing("", "Correct")
        h.editor.invalidate()

        h.selectionChanged(7, 7, 0, 0, settings)

        assertTrue(h.inputLogic.mComposingTracker.isComposing)
    }

    // ── onUpdateSelection: the composing session survives, or does not ──────────

    @Test
    fun externalCursorMoveInsideTheComposingWord_keepsTheSessionWhenSuggestionsAreOn() {
        // One character back inside "hello": the tracker can absorb the delta, so the session
        // survives and only the tracker's internal cursor moves.
        val settings = h.settingsWith("shouldShowLxxButton" to true)
        armComposing("", "hello")
        h.editor.appSetSelection(4, 4)          // the app really moved the caret
        h.editor.dropPendingSelectionEvents()

        assertTrue(h.selectionChanged(5, 5, 4, 4, settings))

        assertTrue(
            "a one-character move inside the word must not end the session",
            h.inputLogic.mComposingTracker.isComposing,
        )
        assertEquals("hello", h.inputLogic.mComposingTracker.composingText)
        assertTrue(h.inputLogic.mComposingTracker.isCursorMoved)
        assertEquals(4, h.inputLogic.mRichInputConnection.cursorStart)
    }

    @Test
    fun externalCursorMoveBeyondTheComposingWord_endsTheSession() {
        // Ten characters back is more than the tracker holds, so moveCursorByCharCount fails
        // and the composing session is torn down.
        val settings = h.settingsWith("shouldShowLxxButton" to true)
        armComposing("some text ", "hello")
        h.editor.appSetSelection(5, 5)
        h.editor.dropPendingSelectionEvents()

        assertTrue(h.selectionChanged(15, 15, 5, 5, settings))

        assertFalse(h.inputLogic.mComposingTracker.isComposing)
        assertEquals(5, h.inputLogic.mRichInputConnection.cursorStart)
    }

    @Test
    fun externalCursorMove_withSuggestionsOff_alwaysEndsTheSession() {
        // `!shouldShowLxxButton` short-circuits straight to the reset, so on a field with no
        // suggestion strip even a one-character move ends composing. Same input as
        // [externalCursorMoveInsideTheComposingWord_keepsTheSessionWhenSuggestionsAreOn].
        armComposing("", "hello")

        assertTrue(h.selectionChanged(5, 5, 4, 4))

        assertFalse(h.inputLogic.mComposingTracker.isComposing)
    }

    @Test
    fun aSelectionBeingCreated_endsTheSessionEvenInsideTheWord() {
        // `z2` is "a selection is involved", and it short-circuits ahead of the tracker check.
        val settings = h.settingsWith("shouldShowLxxButton" to true)
        armComposing("", "hello")

        assertTrue(h.selectionChanged(oldStart = 5, oldEnd = 5, newStart = 2, newEnd = 4, settingsValues = settings))

        assertFalse(h.inputLogic.mComposingTracker.isComposing)
        assertEquals(2, h.inputLogic.mRichInputConnection.cursorStart)
        assertEquals(4, h.inputLogic.mRichInputConnection.cursorEnd)
    }

    @Test
    fun aSelectionBeingDroppedFromAPositionTheConnectionDoesNotShare_isSwallowed() {
        // CHARACTERISED BUG: `isSelectionUnchanged` runs FIRST and never sees the `z2`
        // "a selection is involved" test. Its last two clauses accept any report whose motion
        // points the same way as the connection's own pending motion —
        // `(newStart - oldStart) * (cachedStart - newStart) >= 0` — so a report of
        // "the selection 1..3 collapsed to 4" is treated as a stale echo of a move toward the
        // cached position 5 and the whole update is discarded. The composing session survives a
        // selection change that plainly happened.
        val settings = h.settingsWith("shouldShowLxxButton" to true)
        armComposing("", "hello")

        assertFalse(h.selectionChanged(oldStart = 1, oldEnd = 3, newStart = 4, newEnd = 4, settingsValues = settings))

        assertTrue(h.inputLogic.mComposingTracker.isComposing)
    }

    @Test
    fun aSelectionBeingDropped_endsTheSessionOnceTheConnectionKnowsAboutTheSelection() {
        // The same shape, but with the connection actually holding the selection first, so the
        // stale-echo filter above does not apply: the update goes through and `z2` resets.
        val settings = h.settingsWith("shouldShowLxxButton" to true)
        armComposing("", "hello")
        h.inputLogic.mRichInputConnection.setSelection(1, 3)
        h.editor.dropPendingSelectionEvents()

        assertTrue(h.selectionChanged(oldStart = 1, oldEnd = 3, newStart = 4, newEnd = 4, settingsValues = settings))

        assertFalse(h.inputLogic.mComposingTracker.isComposing)
    }

    @Test
    fun tappingIntoTheMiddleOfAWordWithNothingComposing_justRepositionsTheConnection() {
        val settings = h.settingsWith("shouldShowLxxButton" to true)
        arm("hello world", 11)
        h.editor.appSetSelection(3, 3)
        h.editor.dropPendingSelectionEvents()

        assertTrue(h.selectionChanged(11, 11, 3, 3, settings))

        assertEquals(3, h.inputLogic.mRichInputConnection.cursorStart)
        assertEquals(3, h.inputLogic.mRichInputConnection.cursorEnd)
        assertFalse(h.inputLogic.mComposingTracker.isComposing)
        assertEquals("no text may be touched by a cursor report", "hello world", h.editor.text)
    }

    @Test
    fun theEditorsOwnSelectionEventAfterATypedCharacter_doesNotEndTheSession() {
        // The round trip the pipeline makes on every keystroke: setComposingText moves the
        // cursor, the editor reports it back, and the report must be recognised as one the
        // connection already knows about.
        val settings = h.settingsWith("shouldShowLxxButton" to true)
        h.startSession("", 0)
        h.inputLogic.mRichInputConnection.setComposingText("hell", 1)
        h.seedComposing("hell")
        h.editor.dropPendingSelectionEvents()

        h.inputLogic.mRichInputConnection.setComposingText("hello", 1)
        assertEquals(false, h.deliverSelectionEvent(settings))
        assertTrue(h.inputLogic.mComposingTracker.isComposing)
        assertEquals("hello", h.editor.text)
    }
}
