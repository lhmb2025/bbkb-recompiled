package dev.bbkb.ime.core.textinput

import dev.bbkb.ime.core.keyevent.InputEvent
import dev.bbkb.ime.core.keyevent.InputEventContext
import dev.bbkb.ime.core.keyevent.InputSource
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.settings.util.SettingsValues
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
 * CHARACTERISATION scenarios for `BackspaceController.handleBackspace` — Wave 2 of the
 * feature-simplification plan, written because Wave 4 rewrites the backspace scans and there
 * was no test of this path at all.
 *
 * Each scenario drives the REAL controller against the REAL `InputLogic` /
 * `RichInputConnection` / `ComposingTextTracker` over a
 * [dev.bbkb.ime.harness.FakeEditor], and asserts what reached the EDITOR — the
 * resulting text, cursor, composing region — rather than the controller's private counters.
 * A rewrite that produces the same editor states passes; one that deletes a different number
 * of characters does not.
 *
 * ## Branch map of handleBackspace, in the order the source takes it
 *
 * ```
 * composing && (password || all-digits)      -> cancel composing outright
 * source not INTERNAL/UNKNOWN                -> ++mDeleteRepeatCount
 * shift held && composing                    -> resetComposingAndSelect(clearSuggestions)
 * tracker cursor moved && composing          -> resetComposingAndSelect, then FALL THROUGH
 * selection present                          -> delete the selection, return
 * key repeat && gesture-capable              -> handleAcceleratedDelete, return
 * composing && !wasAutoCorrected             -> shrink the composing buffer (or unlearn in
 *                                               prediction mode), return
 * otherwise:
 *   composing && wasAutoCorrected            -> commit the composing word first
 *   revert eligible && cursor not on a word  -> revertAutoCorrection, return
 *   last committed text sits before cursor   -> delete exactly that text, return
 *   commitType 1 / 2                         -> revertDoubleSpacePeriod / revertSwapPunctuation
 *   no cursor / empty field                  -> raw KEYCODE_DEL
 *   else                                     -> delete one grapheme cluster before the cursor
 * ```
 *
 * The revert branch has its own file: [RevertAutoCorrectionTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class BackspaceScenariosTest {

    private lateinit var h: PipelineHarness
    private lateinit var subtypeStatic: MockedStatic<SubtypeManager>
    private lateinit var settingsStatic: MockedStatic<SettingsManager>

    @Before
    fun setUp() {
        h = PipelineHarness()
        // CommitEventRecord.isRevertEligible() and InputLogic.isCjkLocale() both reach
        // SubtypeManager.getInstance(), which is null outside a running IME.
        subtypeStatic = mockStatic(SubtypeManager::class.java)
        val subtypes = mock(SubtypeManager::class.java)
        `when`(subtypes.currentSubtypeLocale).thenReturn(Locale.US)
        subtypeStatic.`when`<SubtypeManager> { SubtypeManager.getInstance() }.thenReturn(subtypes)

        // SEAM: InputLogic.resetComposingAndSelect -> updateDynamicLearningState reads the
        // process-wide SettingsManager singleton rather than the SettingsValues it was handed,
        // so the composing-reset branches are unreachable without stubbing it. Same pattern as
        // SuggestionBridgeRaceTest.
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

    // ── driving the controller ──────────────────────────────────────────────────

    /**
     * The coordinate a hardware/gesture-capable backspace carries. `hasNoCoordinates()` tests
     * for exactly this value and it selects which of the two accelerated-delete gates applies.
     */
    private val noCoords = -4

    private fun backspaceEvent(repeat: Boolean = false, x: Int = noCoords): InputEvent =
        InputEvent.createKeyPress(-1, -5, x, x, 1000L, repeat)

    /**
     * Send one backspace through the controller and hand back the context, whose
     * `shouldUpdateSuggestions` / `getUiUpdateMode` are part of the decision.
     */
    private fun backspace(
        settings: SettingsValues = h.settings,
        repeat: Boolean = false,
        x: Int = noCoords,
        shiftPressed: Boolean = false,
        source: InputSource = InputSource.HARDWARE,
        commitType: Int = 0,
        symbolPageOrder: Int = 0,
    ): InputEventContext {
        val event = backspaceEvent(repeat, x)
        val ctx = InputEventContext(settings, event, 1000L, commitType, symbolPageOrder)
        ctx.setInputSource(source)
        ctx.setShiftPressed(shiftPressed)
        h.inputLogic.mBackspaceController.handleBackspace(event, ctx, settings, symbolPageOrder)
        return ctx
    }

    /** "text|cursor" rendering of the editor, so a failure message shows the whole state. */
    private fun editorState(): String {
        val t = h.editor.text
        val c = h.editor.selStart
        val e = h.editor.selEnd
        val marker = if (c == e) "|" else "|..$e.."
        return t.substring(0, c) + marker + t.substring(c)
    }

    /** Arm a live composing region in the editor AND the tracker, the way typing would. */
    private fun startComposing(prefix: String, word: String) {
        h.startSession(prefix, prefix.length)
        h.inputLogic.mRichInputConnection.setComposingText(word, 1)
        h.seedComposing(word)
        h.editor.dropPendingSelectionEvents()
    }

    // ── plain characters ────────────────────────────────────────────────────────

    @Test
    fun backspace_overAPlainCharacter_deletesExactlyOne() {
        h.startSession("hello", 5)
        backspace()
        assertEquals("hell|", editorState())
    }

    @Test
    fun backspace_overASpace_deletesTheSpaceOnly() {
        h.startSession("hello world", 6)   // "hello |world"
        backspace()
        assertEquals("hello|world", editorState())
    }

    @Test
    fun backspace_midText_deletesBeforeTheCursorNotAfterIt() {
        // The comment above the `cpAfter != -1 && shiftHeld` branch records that this used to
        // forward-delete when getTextBeforeCursor came back empty. Pin the direction.
        h.startSession("abcdef", 3)
        backspace()
        assertEquals("ab|def", editorState())
    }

    @Test
    fun repeatedBackspaces_walkTheFieldToEmptyOneCharacterAtATime() {
        h.startSession("abc", 3)
        backspace(); assertEquals("ab|", editorState())
        backspace(); assertEquals("a|", editorState())
        backspace(); assertEquals("|", editorState())
    }

    // ── the empty / start-of-field edges ────────────────────────────────────────

    @Test
    fun backspace_atStartOfANonEmptyField_deletesNothingAndSendsNoKeyEvent() {
        // Cursor at 0 with text after it: `getCursorEnd() == 0` but `getCodePointAfterCursor()`
        // is not -1, so the raw-key branch is NOT taken. cpBefore is -1, so the controller
        // takes the `deleteSurroundingText(1, 0)` shortcut — which deletes nothing, there being
        // nothing before the cursor.
        h.startSession("hello", 0)
        backspace()
        assertEquals("|hello", editorState())
        assertTrue("no raw key event should be sent here", h.editor.sentKeyEvents.isEmpty())
    }

    @Test
    fun backspace_onACompletelyEmptyField_fallsBackToARawKeyEvent() {
        // cursorEnd == 0 AND nothing before AND nothing after -> the raw KEYCODE_DEL branch,
        // which is how the IME lets the app (a list, a chip field, a webview) handle it.
        h.startSession("", 0)
        backspace()
        assertEquals("|", editorState())
        assertEquals(
            "one down + one up KEYCODE_DEL",
            listOf(67, 67), h.editor.sentKeyEvents.map { it.keyCode },
        )
    }

    // ── selections ──────────────────────────────────────────────────────────────

    @Test
    fun backspace_withASelection_deletesTheWholeSelection() {
        h.startSession("hello world", 11)
        h.inputLogic.mRichInputConnection.setSelection(6, 11)
        h.editor.dropPendingSelectionEvents()
        backspace()
        assertEquals("hello |", editorState())
    }

    @Test
    fun backspace_withASingleCharacterSelection_deletesThatCharacter() {
        h.startSession("abc", 3)
        h.inputLogic.mRichInputConnection.setSelection(1, 2)
        h.editor.dropPendingSelectionEvents()
        backspace()
        assertEquals("a|c", editorState())
    }

    // ── composing text ──────────────────────────────────────────────────────────

    @Test
    fun backspace_whileComposing_shrinksTheComposingWordByOne() {
        startComposing("", "hello")
        backspace()
        assertEquals("hell|", editorState())
        assertEquals("hell", h.inputLogic.mComposingTracker.composingText)
        assertTrue(h.inputLogic.mComposingTracker.isComposing)
        assertTrue("the region must survive", h.editor.hasComposingRegion())
    }

    @Test
    fun backspace_emptyingTheComposingWord_commitsEmptyAndEndsTheSession() {
        startComposing("", "a")
        backspace()
        assertEquals("|", editorState())
        assertFalse(h.inputLogic.mComposingTracker.isComposing)
        assertFalse(
            "an emptied composing word must not leave a region behind",
            h.editor.hasComposingRegion(),
        )
    }

    @Test
    fun backspace_deletingAWholeComposingWord_takesOneKeystrokePerCharacter() {
        // The "delete a whole composing word" case: there is no single-keystroke word delete on
        // the ordinary backspace path — that lives on the swipe gesture — so the word comes off
        // one character at a time and the region shrinks with it.
        startComposing("say ", "hello")
        val seen = ArrayList<String>()
        repeat(5) { backspace(); seen.add(h.editor.composingText) }
        assertEquals(listOf("hell", "hel", "he", "h", ""), seen)
        assertEquals("say |", editorState())
        assertFalse(h.inputLogic.mComposingTracker.isComposing)
    }

    @Test
    fun backspace_whileComposingInPredictionMode_dropsTheWholeWordAndUnlearnsIt() {
        // Prediction mode (a gesture-committed word still open for editing) does NOT shrink by
        // one: the whole word is discarded in a single keystroke and unlearned from the DLM.
        startComposing("", "hello")
        h.inputLogic.mComposingTracker.enterPredictionMode()
        backspace()
        assertEquals("|", editorState())
        assertFalse(h.inputLogic.mComposingTracker.isComposing)
        assertEquals("hello", h.inputLogic.mComposingTracker.lastCommittedWord)
        org.mockito.Mockito.verify(h.dictionaryLoader).unlearnWord("hello")
    }

    @Test
    fun backspace_onAnAllDigitComposingWord_cancelsComposingThenDeletesACharacter() {
        // The all-digits guard runs FIRST and cancels composing outright; the keystroke then
        // falls through the ordinary path, so a digit is still deleted.
        startComposing("", "123")
        backspace()
        assertEquals("12|", editorState())
        assertFalse(h.inputLogic.mComposingTracker.isComposing)
        assertFalse(h.editor.hasComposingRegion())
    }

    @Test
    fun backspace_withTheTrackerCursorMovedInsideTheWord_stillDeletesACharacter() {
        // The regression the source comments on: this branch used to truncate the composing
        // buffer and RETURN, making the first backspace a visible no-op. It now resets
        // composing and falls through, so a character really goes.
        startComposing("", "hello")
        h.inputLogic.mRichInputConnection.setSelection(3, 3)
        h.editor.dropPendingSelectionEvents()
        h.inputLogic.mComposingTracker.setComposingCursorPosition(3)
        assertTrue("precondition: the tracker considers its cursor moved",
            h.inputLogic.mComposingTracker.isCursorMoved)

        backspace()
        assertEquals("he|lo", editorState())
        assertFalse(h.inputLogic.mComposingTracker.isComposing)
    }

    // ── shift + backspace = forward delete ──────────────────────────────────────

    @Test
    fun shiftBackspace_deletesForward() {
        h.startSession("abcdef", 3)
        backspace(shiftPressed = true)
        assertEquals("abc|ef", editorState())
    }

    @Test
    fun shiftBackspace_withNothingAfterTheCursor_deletesBackwardInstead() {
        // `cpAfter != -1 && shiftHeld` fails on the first clause, so the ordinary backward
        // delete runs — shift is not a hard forward-only mode.
        h.startSession("abc", 3)
        backspace(shiftPressed = true)
        assertEquals("ab|", editorState())
    }

    // ── surrogate pairs / emoji ─────────────────────────────────────────────────

    @Test
    fun backspace_overAnEmoji_deletesTheWholeCodePointNotHalfOfIt() {
        // U+1F600, two chars in the editor's buffer. A scan that counts CHARS rather than code
        // points leaves a lone high surrogate behind — this is exactly the case Wave 4's rewrite
        // has to keep getting right. Today the grapheme scan handles it.
        val grin = "😀"
        h.startSession("hi $grin", 5)
        backspace()
        assertEquals("hi |", editorState())
        assertEquals("hi ", h.editor.text)
    }

    @Test
    fun backspace_overAMultiCodePointGraphemeCluster_deletesTheWholeCluster() {
        // Family emoji: four people joined by ZWJs, 11 chars. The delete is grapheme-sized, so
        // one keystroke takes the whole cluster rather than peeling one person off it.
        val family = "👨‍👩‍👦"
        h.startSession("x$family", 1 + family.length)
        backspace()
        assertEquals("x|", editorState())
    }

    @Test
    fun backspace_overAnEmojiWhileComposing_shrinksTheTrackerByOneCodePoint() {
        // The composing path goes through the tracker rather than the grapheme scan, so this is
        // a genuinely different code path over the same input.
        val grin = "😀"
        startComposing("", "a$grin")
        backspace()
        assertEquals("a|", editorState())
        assertEquals("a", h.inputLogic.mComposingTracker.composingText)
    }

    // ── the "undo the last commit" branch ───────────────────────────────────────

    @Test
    fun backspace_afterACommittedSuggestion_removesTheWholeCommittedWord() {
        // mLastCommittedText is set when a suggestion is committed. While that exact text still
        // sits before the cursor, ONE backspace removes all of it — not one character.
        h.startSession("say hello", 9)
        h.inputLogic.mLastCommittedText = "hello"
        backspace()
        assertEquals("say |", editorState())
    }

    @Test
    fun backspace_twiceAfterACommittedSuggestion_thenFallsBackToCharacterDeletes() {
        // The flag is one-shot: the second keystroke is an ordinary single-character delete.
        h.startSession("say hello", 9)
        h.inputLogic.mLastCommittedText = "hello"
        backspace()
        assertEquals("say |", editorState())
        backspace()
        assertEquals("say|", editorState())
    }

    @Test
    fun backspace_whenTheCommittedTextNoLongerSitsBeforeTheCursor_isAnOrdinaryDelete() {
        // The guard is `isTextBeforeCursor(lastCommitted)`; with the cursor moved off the word
        // the whole-word branch must not fire.
        h.startSession("say hello there", 15)
        h.inputLogic.mLastCommittedText = "hello"
        backspace()
        assertEquals("say hello ther|", editorState())
    }

    @Test
    fun backspace_afterACommittedEmoji_removesTheWholeGraphemeCluster() {
        val grin = "😀"
        h.startSession("hi $grin", 5)
        h.inputLogic.mLastCommittedText = grin
        backspace()
        assertEquals("hi |", editorState())
    }

    // ── accelerated / key-repeat delete ─────────────────────────────────────────

    /**
     * The accelerated path is gated on the IME advertising gesture input, so it only exists on
     * a gesture-capable device. Both gates are asserted here rather than assumed.
     */
    private fun armAcceleratedDelete() {
        `when`(h.ime.isGestureInputReady()).thenReturn(true)
    }

    @Test
    fun keyRepeat_withoutGestureCapability_takesTheOrdinaryPath() {
        // isGestureInputReady() false: the repeat flag alone does NOT reach handleAcceleratedDelete.
        h.startSession("abcdef", 6)
        backspace(repeat = true)
        assertEquals("abcde|", editorState())
    }

    @Test
    fun acceleratedDelete_firstRepeat_deletesOneCharacter() {
        // DEFECT 4, now fixed. The first accelerated repeat used to fall into the
        // `mDeleteWordCount == 0 && mDeleteAccelThreshold == 0` arm, set the threshold to 2 and
        // return having deleted NOTHING — so holding backspace on a gesture-capable device
        // swallowed a repeat and then jumped straight to word deletes without ever removing a
        // single character. It now arms the word-delete threshold AND deletes a character, the
        // same as the ordinary (non-gesture) repeat path does.
        armAcceleratedDelete()
        h.startSession("abcdef", 6)
        backspace(repeat = true)
        assertEquals("abcde|", editorState())
    }

    @Test
    fun acceleratedDelete_takesOneCharacterPerRepeat_thenEscalatesToWords() {
        // DEFECT 4: the acceleration curve. It used to be "nothing, one word, then eight dead
        // repeats, then one word" — the threshold started at 2 and jumped by (10 - wordCount).
        // Hold-to-delete now removes one character per repeat and only escalates to whole-word
        // deletes at repeat 40, the same count the non-accelerated path escalates at.
        armAcceleratedDelete()
        val text = "alpha bravo charlie delta echo foxtrot golf hotel india juliett kilo lima"
        h.startSession(text, text.length)

        repeat(39) { backspace(repeat = true) }
        assertEquals(
            "the 39 repeats below the escalation point each take exactly one character",
            text.substring(0, text.length - 39), h.editor.text,
        )

        val lengthBeforeEscalation = h.editor.text.length
        backspace(repeat = true)   // repeat 40 — the escalation point
        val taken = lengthBeforeEscalation - h.editor.text.length
        assertTrue(
            "the fortieth repeat must take a whole word, not one character (took $taken)",
            taken > 1,
        )
    }

    @Test
    fun acceleratedDelete_withNothingComposingAndNoTextBefore_deletesForwardByOne() {
        // cpBefore == -1 and not composing: a single deleteSurroundingText(1, 0), which at
        // position 0 removes nothing.
        armAcceleratedDelete()
        h.startSession("abc", 0)
        backspace(repeat = true)
        assertEquals("|abc", editorState())
    }

    @Test
    fun acceleratedDelete_withShiftHeld_deletesForward() {
        armAcceleratedDelete()
        h.startSession("abcdef", 3)
        backspace(repeat = true, shiftPressed = true)
        assertEquals("abc|ef", editorState())
    }

    @Test
    fun acceleratedDelete_withPredictionsOn_clearsWasAutoCorrectedAndRecorrects() {
        // DEFECT 4 changed which repeats reach here: it used to be the repeats that deleted
        // NOTHING, and it is now the character repeats — a character delete followed by
        // recorrection, the same pairing the non-accelerated path uses. Only a word-delete
        // repeat suppresses it. Recorrection also stamps wasAutoCorrected back to false; with
        // shouldShowLxxButton off, performRecorrection bails out to a neutral strip, and that
        // call is the observable trace.
        armAcceleratedDelete()
        val settings = h.settingsWith("isPredictionsEnabled" to true)
        h.startSession("hello world there", 17)
        h.inputLogic.mComposingTracker.setWasAutoCorrected(true)

        backspace(settings = settings, repeat = true)

        assertEquals("hello world ther|", editorState())
        assertFalse(h.inputLogic.mComposingTracker.wasAutoCorrected())
        org.mockito.Mockito.verify(h.suggestionStripListener, org.mockito.Mockito.atLeastOnce())
            .setNeutralSuggestionStrip()
    }

    @Test
    fun acceleratedDelete_withPredictionsOff_neverRecorrects() {
        armAcceleratedDelete()
        h.startSession("hello world there", 17)
        repeat(3) { backspace(repeat = true) }
        org.mockito.Mockito.verifyNoInteractions(h.suggestionStripListener)
    }

    // ── accelerated delete over a LIVE composing word ───────────────────────────

    @Test
    fun acceleratedDelete_whileComposing_shrinksTheComposingWordInsteadOfDeletingAroundIt() {
        // FIX-BKSP. Holding backspace on a KEY2 routes every repeat to handleAcceleratedDelete,
        // which used to call deleteSurroundingText(1, 0) with a LIVE composing region. That call
        // is defined to leave the composing text alone and delete the text AROUND it, so on a
        // real editor each repeat ate a character of the already-committed sentence while the
        // half-typed word sat there unchanged — the user-visible "letters jam together".
        // The repeat must shrink the composing word, exactly as a non-repeat backspace does.
        armAcceleratedDelete()
        startComposing("say ", "union")

        backspace(repeat = true)

        assertEquals("say unio|", editorState())
        assertEquals("unio", h.inputLogic.mComposingTracker.composingText)
        assertTrue("the composing region must survive", h.editor.hasComposingRegion())
        assertEquals("unio", h.editor.composingText)
        assertTrue(
            "a repeat over a composing word must never call deleteSurroundingText: " + h.editor.callLog,
            h.editor.callLog.none { it.startsWith("deleteSurroundingText") },
        )
    }

    @Test
    fun acceleratedDelete_whileComposing_leavesTheCommittedTextBeforeItUntouched() {
        // The reported symptom, three repeats deep: the committed prefix must be byte-identical
        // and the tracker must stay in step with the editor (a tracker that still said "union"
        // would rewrite the whole word back over the region on the next keystroke).
        armAcceleratedDelete()
        startComposing("Hello there ", "union")

        repeat(3) { backspace(repeat = true) }

        assertEquals("Hello there un|", editorState())
        assertEquals("un", h.inputLogic.mComposingTracker.composingText)
        assertTrue(h.inputLogic.mComposingTracker.isComposing)
    }

    @Test
    fun acceleratedDelete_emptyingTheComposingWord_endsTheSessionLikeTheOrdinaryPath() {
        // Same end state as backspace_emptyingTheComposingWord_commitsEmptyAndEndsTheSession,
        // reached through the repeat path.
        armAcceleratedDelete()
        startComposing("say ", "a")

        backspace(repeat = true)

        assertEquals("say |", editorState())
        assertFalse(h.inputLogic.mComposingTracker.isComposing)
        assertFalse(h.editor.hasComposingRegion())
    }

    @Test
    fun acceleratedDelete_whileComposingInPredictionMode_dropsTheWholeWordAndUnlearnsIt() {
        // The prediction-mode arm of the shared composing branch, over the repeat path.
        armAcceleratedDelete()
        startComposing("", "hello")
        h.inputLogic.mComposingTracker.enterPredictionMode()

        backspace(repeat = true)

        assertEquals("|", editorState())
        assertFalse(h.inputLogic.mComposingTracker.isComposing)
        org.mockito.Mockito.verify(h.dictionaryLoader).unlearnWord("hello")
    }

    @Test
    fun backspace_whileComposing_behavesTheSameOnBothPaths() {
        // (b) The non-repeat path is the reference implementation and must not have moved:
        // drive the identical scenario through it and compare against the accelerated run in
        // acceleratedDelete_whileComposing_shrinksTheComposingWordInsteadOfDeletingAroundIt.
        startComposing("say ", "union")

        backspace(repeat = false)

        assertEquals("say unio|", editorState())
        assertEquals("unio", h.inputLogic.mComposingTracker.composingText)
        assertTrue(h.editor.hasComposingRegion())
    }

    @Test
    fun acceleratedWordDelete_overAComposingWord_clearsThatWordAndStopsThere() {
        // (c) Escalation still lands on the same repeat: seed the count so the next repeat is
        // the fortieth. "Delete a word" while a word is composing means THAT word — the
        // uncommitted one the cursor sits in — not the committed word to its left. deleteWord
        // -> handleSwipeDelete -> deleteByWordSwipe already sizes the delete from the composing
        // tracker for exactly this reason.
        armAcceleratedDelete()
        startComposing("say hello ", "union")
        h.inputLogic.mBackspaceController.mDeleteRepeatCount = 39

        backspace(repeat = true)

        assertEquals("say hello |", editorState())
        assertFalse(h.inputLogic.mComposingTracker.isComposing)
        assertFalse(h.editor.hasComposingRegion())
    }

    @Test
    fun acceleratedDelete_belowTheEscalationPoint_neverWordDeletesAComposingWord() {
        // The mirror of the test above: one repeat below the threshold takes one character, so
        // the composing branch cannot be smuggling a word delete in.
        armAcceleratedDelete()
        startComposing("say hello ", "union")
        h.inputLogic.mBackspaceController.mDeleteRepeatCount = 38

        backspace(repeat = true)

        assertEquals("say hello unio|", editorState())
        assertTrue(h.inputLogic.mComposingTracker.isComposing)
    }

    @Test
    fun acceleratedDelete_withNoComposingRegion_isUnchangedByTheComposingBranch() {
        // (d) The non-composing repeat path, end to end over the escalation boundary, asserted
        // here as well as in acceleratedDelete_takesOneCharacterPerRepeat_thenEscalatesToWords
        // so that a regression in the composing branch cannot pass unnoticed.
        armAcceleratedDelete()
        h.startSession("alpha bravo", 11)
        repeat(3) { backspace(repeat = true) }
        assertEquals("alpha br|", editorState())
        assertFalse(h.inputLogic.mComposingTracker.isComposing)
    }

    @Test
    fun acceleratedDelete_overAnAutoCorrectedComposingWord_commitsItBeforeDeletingAroundIt() {
        // The second entrance to the same hole. `wasAutoCorrected` sends the keystroke past the
        // composing branch, and the character delete below it used to run with the region still
        // live — "say hello" came back as "sayhello", the space eaten and the word untouched.
        // The ordinary path opens its else-arm by committing the auto-corrected word first, so
        // this path does too: the region is finished, then one character goes off the end.
        // Reachable after a swiped word, whose first backspace recorrects and stamps
        // wasAutoCorrected while a region is open; the repeats that follow land here.
        armAcceleratedDelete()
        startComposing("say ", "hello")
        h.inputLogic.mComposingTracker.setWasAutoCorrected(true)

        backspace(repeat = true)

        assertEquals("say hell|", editorState())
        assertFalse("the composing region must be finished, not deleted around",
            h.editor.hasComposingRegion())
    }

    // ── double-space-period revert (commitType 1) ───────────────────────────────

    @Test
    fun backspace_afterADoubleSpacePeriod_putsASingleSpaceBack() {
        // The substitution turned "hi " + a second space into "hi. "; the revert replaces the
        // whole ". " with one space, so the field goes back to "hi " and the keystroke deletes
        // no character of its own.
        h.startSession("hi. ", 4)
        backspace(commitType = 1)
        assertEquals("hi |", editorState())
    }

    @Test
    fun backspace_withCommitTypeOne_butNoPeriodSpaceBeforeCursor_isAnOrdinaryDelete() {
        h.startSession("hello", 5)
        backspace(commitType = 1)
        assertEquals("hell|", editorState())
    }
}
