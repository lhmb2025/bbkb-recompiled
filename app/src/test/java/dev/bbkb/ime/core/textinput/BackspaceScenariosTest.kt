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
import org.robolectric.util.ReflectionHelpers
import java.util.Locale

/**
 * Characterises `BackspaceController.handleBackspace`: the real controller, InputLogic,
 * RichInputConnection and tracker over a [dev.bbkb.ime.harness.FakeEditor], asserting what reached
 * the editor (text, cursor, composing region).
 *
 * Branch order of handleBackspace:
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
 * The revert branch is covered by [RevertAutoCorrectionTest].
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

        // resetComposingAndSelect -> updateDynamicLearningState reads the SettingsManager singleton,
        // so the composing-reset branches need it stubbed.
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

    /** Hardware/gesture backspace coordinate: `hasNoCoordinates()` tests for it and it picks the accelerated-delete gate. */
    private val noCoords = -4

    private fun backspaceEvent(repeat: Boolean = false, x: Int = noCoords): InputEvent =
        InputEvent.createKeyPress(-1, -5, x, x, 1000L, repeat)

    /** Sends one backspace and returns the context, whose update flags are part of the decision. */
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
        h.startSession("abcdef", 3)
        backspace()
        assertEquals("ab|def", editorState())
    }

    /**
     * Some editors return "" from getTextBeforeCursor() while a composing span is active. The gate is
     * the tracked cursor position, so this still deletes to the left, not the right.
     */
    @Test
    fun backspace_midText_withAnEmptyBeforeCursorRead_stillDeletesBeforeTheCursor() {
        h.startSession("abcdef", 3)
        val cached = ReflectionHelpers.getField<StringBuilder>(h.inputLogic.mRichInputConnection, "mTextBeforeCursor")
        cached.setLength(0)
        assertEquals(-1, h.inputLogic.mRichInputConnection.getCodePointBeforeCursor())

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

    @Test
    fun backspace_atStartOfANonEmptyField_deletesTheCharacterAfterTheCursor() {
        // Cursor at 0 with text after it: not the raw-key branch. Nothing can go to the left, so DEL
        // deletes to the right.
        h.startSession("hello", 0)
        backspace()
        assertEquals("|ello", editorState())
        assertTrue("no raw key event should be sent here", h.editor.sentKeyEvents.isEmpty())
    }

    @Test
    fun repeatedBackspacesAtStartOfField_eatTheFieldFromTheFront() {
        h.startSession("abc", 0)
        backspace(); assertEquals("|bc", editorState())
        backspace(); assertEquals("|c", editorState())
        backspace(); assertEquals("|", editorState())
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
        // No single-keystroke word delete on this path (that is the swipe gesture): the word comes off
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
        // This branch resets composing and falls through, so the first backspace really deletes.
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

    @Test
    fun shiftBackspace_deletesForward() {
        h.startSession("abcdef", 3)
        backspace(shiftPressed = true)
        assertEquals("abc|ef", editorState())
    }

    @Test
    fun shiftBackspace_withNothingAfterTheCursor_deletesBackwardInstead() {
        // `cpAfter != -1 && shiftHeld` fails on the first clause, so the ordinary backward
        // delete runs: shift is not a hard forward-only mode.
        h.startSession("abc", 3)
        backspace(shiftPressed = true)
        assertEquals("ab|", editorState())
    }

    @Test
    fun shiftBackspace_overAnEmojiFollowedByText_deletesOnlyTheEmoji() {
        // Forward delete uses the same leading-emoji scan as a cursor step right.
        h.startSession("😀bc", 0)
        backspace(shiftPressed = true)
        assertEquals("|bc", editorState())
    }

    @Test
    fun backspace_overAnEmoji_deletesTheWholeCodePointNotHalfOfIt() {
        // U+1F600 is two chars; a scan counting chars rather than code points leaves a lone high surrogate.
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
    fun backspace_overANewerEmojiWithASkinTone_deletesTheWholeEmoji() {
        // U+1FAF6 + medium skin tone: the scan must know the U+1FAxx blocks.
        val hands = "🫶🏽"
        h.startSession("hi $hands", 3 + hands.length)
        backspace()
        assertEquals("hi |", editorState())
    }

    @Test
    fun backspace_overTwoFlags_deletesOneFlagAtATime() {
        h.startSession("🇺🇸🇬🇧", 8)
        backspace()
        assertEquals("🇺🇸|", editorState())
        backspace()
        assertEquals("|", editorState())
    }

    @Test
    fun backspace_overADevanagariSyllable_deletesTheVowelSignAlone() {
        // क + ि: deletes go piece by piece in Indic text, as the platform's own field does.
        h.startSession("कि", 2)
        backspace()
        assertEquals("क|", editorState())
    }

    @Test
    fun shiftBackspace_overAKeycap_deletesTheWholeKeycap() {
        h.startSession("1️⃣x", 0)
        backspace(shiftPressed = true)
        assertEquals("|x", editorState())
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

    @Test
    fun backspace_afterACommittedSuggestion_removesTheWholeCommittedWord() {
        // mLastCommittedText is set when a suggestion is committed. While that exact text still
        // sits before the cursor, one backspace removes all of it, not one character.
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

    /** Arms the accelerated path, which is gated on the IME advertising gesture input. */
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
        // The first accelerated repeat arms the word-delete threshold and also deletes a character.
        armAcceleratedDelete()
        h.startSession("abcdef", 6)
        backspace(repeat = true)
        assertEquals("abcde|", editorState())
    }

    @Test
    fun acceleratedDelete_takesOneCharacterPerRepeat_thenEscalatesToWords() {
        // One character per repeat, escalating to whole-word deletes at repeat 40 (the same count as
        // the non-accelerated path).
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
    fun acceleratedDelete_atStartOfField_deletesForwardByOne() {
        // The hold reaches the start of the field: each repeat takes one character from the
        // front, as the ordinary path does.
        armAcceleratedDelete()
        h.startSession("abc", 0)
        backspace(repeat = true)
        assertEquals("|bc", editorState())
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
        // Character repeats pair a delete with recorrection; only a word-delete repeat suppresses it.
        // With shouldShowLxxButton off, performRecorrection bails to a neutral strip: the observable trace.
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

    @Test
    fun acceleratedDelete_whileComposing_shrinksTheComposingWordInsteadOfDeletingAroundIt() {
        // deleteSurroundingText(1, 0) with a live composing region deletes around the region, not in it,
        // so each repeat must shrink the composing word like a non-repeat backspace.
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
        // The committed prefix must be untouched and the tracker in step with the editor (a stale
        // tracker would rewrite the old word over the region on the next keystroke).
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
        // (b) The non-repeat path is the reference: the same scenario as
        // acceleratedDelete_whileComposing_shrinksTheComposingWordInsteadOfDeletingAroundIt.
        startComposing("say ", "union")

        backspace(repeat = false)

        assertEquals("say unio|", editorState())
        assertEquals("unio", h.inputLogic.mComposingTracker.composingText)
        assertTrue(h.editor.hasComposingRegion())
    }

    @Test
    fun acceleratedWordDelete_overAComposingWord_clearsThatWordAndStopsThere() {
        // (c) Seed the count so the next repeat is the fortieth. A word delete while composing takes the
        // composing word (deleteByWordSwipe sizes it from the tracker), not the committed word before it.
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
        // (d) The non-composing repeat path across the escalation boundary, so a composing-branch
        // change cannot break it unnoticed.
        armAcceleratedDelete()
        h.startSession("alpha bravo", 11)
        repeat(3) { backspace(repeat = true) }
        assertEquals("alpha br|", editorState())
        assertFalse(h.inputLogic.mComposingTracker.isComposing)
    }

    @Test
    fun acceleratedDelete_overAnAutoCorrectedComposingWord_commitsItBeforeDeletingAroundIt() {
        // wasAutoCorrected skips the composing branch, so this path commits the word first (else
        // "say hello" becomes "sayhello"), then deletes one character. Reachable after a swiped word.
        armAcceleratedDelete()
        startComposing("say ", "hello")
        h.inputLogic.mComposingTracker.setWasAutoCorrected(true)

        backspace(repeat = true)

        assertEquals("say hell|", editorState())
        assertFalse("the composing region must be finished, not deleted around",
            h.editor.hasComposingRegion())
    }

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
