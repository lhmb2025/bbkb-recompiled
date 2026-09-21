package dev.bbkb.ime.core.textinput

import dev.bbkb.ime.core.keyevent.InputEvent
import dev.bbkb.ime.core.keyevent.InputEventContext
import dev.bbkb.ime.core.keyevent.InputSource
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.core.textinput.composing.CommitEventRecord
import dev.bbkb.ime.harness.PipelineHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * CHARACTERISATION tests for the revert-auto-correction path — `RecorrectionController`
 * .revertAutoCorrection, reached through `BackspaceController.handleBackspace`.
 *
 * This is the behaviour a user notices most ("it changed my word and one backspace put it
 * back") and it had no test at all. Wave 3/4 touches both the gate in `handleBackspace` and
 * the body of `revertAutoCorrection`, so both halves are pinned here:
 *
 *  - **the gate**: `mEventDispatcher.isRevertEligible() && (!isFollowedBySpaceSymbol ||
 *    event.mX == COORD_SWIPE_DELETE_REVERT)` — defect 3 removed a word-separator test from
 *    this gate that no real post-auto-correction state could satisfy
 *  - **the body**: how much is deleted, what is put back, and whether what comes back is
 *    COMPOSING (still open for editing) or COMMITTED — which is decided entirely by whether
 *    the recorded word separator is really sitting before the cursor.
 *
 * The second backspace after a revert is asserted too: that is the keystroke users hit when
 * the revert was not what they wanted, and it behaves differently in the two cases.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class RevertAutoCorrectionTest {

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

        val sm = mock(SettingsManager::class.java)
        `when`(sm.getSettingsValues()).thenReturn(h.settings)
        settingsStatic = mockStatic(SettingsManager::class.java)
        settingsStatic.`when`<SettingsManager> { SettingsManager.getInstance() }.thenReturn(sm)

        // The revert re-opens the typed word as composing text, which needs key coordinates for
        // it; the mock IME would hand back null. The no-coordinate sentinel is what
        // PipelineHarness.seedComposing uses.
        `when`(h.ime.getKeyCoordinates(anyArg())).thenAnswer { inv ->
            IntArray((inv.arguments[0] as IntArray).size * 2) { -1 }
        }
    }

    /**
     * `ArgumentMatchers.any()` hands back `null`, which Kotlin refuses to pass to a
     * non-nullable parameter. The matcher is still registered by the call, so casting the null
     * through an unbounded type parameter is the standard way to use it from Kotlin.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(): T = any<T>() as T

    @After
    fun tearDown() {
        settingsStatic.close()
        subtypeStatic.close()
        h.close()
    }

    // ── setup helpers ───────────────────────────────────────────────────────────

    /**
     * Put the pipeline in the state that exists immediately after an auto-correction: the
     * editor holds [committed], and the commit record remembers what the user actually typed.
     */
    private fun afterAutoCorrection(
        editorText: String,
        cursor: Int,
        typed: String,
        committed: String,
        separator: String,
    ) {
        h.startSession(editorText, cursor)
        h.inputLogic.mEventDispatcher = CommitEventRecord(
            null, typed, committed, separator, null, 0,
            CommitEventRecord.CommitType.DECIDED_WORD, false,
        )
        h.editor.dropPendingSelectionEvents()
    }

    private val noCoords = -4
    private val swipeDeleteRevert = InputEvent.COORD_SWIPE_DELETE_REVERT

    private fun backspace(
        x: Int = noCoords,
        commitType: Int = 0,
        settings: SettingsValues = h.settings,
    ): InputEventContext {
        val event = InputEvent.createKeyPress(-1, -5, x, x, 1000L, false)
        val ctx = InputEventContext(settings, event, 1000L, commitType, 0)
        ctx.setInputSource(InputSource.HARDWARE)
        h.inputLogic.mBackspaceController.handleBackspace(event, ctx, settings, 0)
        return ctx
    }

    private fun editorState(): String {
        val t = h.editor.text
        val c = h.editor.selStart
        return t.substring(0, c) + "|" + t.substring(c)
    }

    // ── the gate ────────────────────────────────────────────────────────────────

    @Test
    fun revertFires_whenTheCharacterBeforeTheCursorIsPartOfTheWord() {
        // No separator typed yet: the cursor sits right after the corrected word, so
        // `cpBefore` is a letter and the gate opens.
        afterAutoCorrection("the", 3, typed = "teh", committed = "the", separator = "")
        assertTrue(h.inputLogic.mEventDispatcher.isRevertEligible())
        backspace()
        assertEquals("teh|", editorState())
    }

    @Test
    fun revertFires_whenTheSeparatorTypedWithTheCorrectionSitsBeforeTheCursor() {
        // DEFECT 3, now fixed. This is the case every user hits: auto-correct commits on
        // space, so the character before the cursor when the backspace arrives is the space
        // that was committed together with the correction. The gate used to demand word
        // content there — a state no post-auto-correction cursor can be in — so keyboard
        // backspace never reverted a correction at all; only the swipe-delete coordinate
        // reached `revertAutoCorrection`. One backspace now puts the typed word back along
        // with its separator.
        afterAutoCorrection("the ", 4, typed = "teh", committed = "the", separator = " ")
        assertTrue(h.inputLogic.mEventDispatcher.isRevertEligible())
        backspace()
        assertEquals("teh |", editorState())
        assertSame(
            "the record must go idle so a second backspace cannot revert again",
            CommitEventRecord.IDLE, h.inputLogic.mEventDispatcher,
        )
    }

    @Test
    fun revertDoesNotFire_whenAFollowedBySpaceSymbolSitsBeforeTheCursor() {
        // DEFECT 3 kept one exclusion: `symbols_followed_by_space` is ".,;:!?)]}&", and when
        // one of those sits before the cursor the keystroke has to fall through to the
        // double-space-period / swap-punctuation reverts further down `handleBackspace`, which
        // own the undo for those. So the auto-correct revert stays shut here and the keystroke
        // is an ordinary character delete.
        afterAutoCorrection("the.", 4, typed = "teh", committed = "the", separator = ".")
        assertTrue(h.inputLogic.mEventDispatcher.isRevertEligible())
        backspace()
        assertEquals("the|", editorState())
        assertSame(
            "the commit record must survive a non-revert backspace",
            "teh", h.inputLogic.mEventDispatcher.typedWord,
        )
    }

    @Test
    fun revertFires_afterASeparator_whenTheEventCarriesTheSwipeDeleteCoordinate() {
        // The `event.mX == COORD_SWIPE_DELETE_REVERT` arm: a swipe-to-delete right after an
        // auto-correction reverts it whatever sits before the cursor — including the
        // followed-by-space punctuation the gate itself excludes.
        afterAutoCorrection("the ", 4, typed = "teh", committed = "the", separator = " ")
        backspace(x = swipeDeleteRevert)
        assertEquals("teh |", editorState())
    }

    @Test
    fun revertDoesNotFire_whenTheTypedWordEqualsTheCommittedWord() {
        // isRevertEligible() is false when nothing was actually corrected.
        afterAutoCorrection("the", 3, typed = "the", committed = "the", separator = "")
        assertFalse(h.inputLogic.mEventDispatcher.isRevertEligible())
        backspace()
        assertEquals("th|", editorState())
    }

    @Test
    fun revertDoesNotFire_afterTheRecordHasBeenDisabled() {
        afterAutoCorrection("the", 3, typed = "teh", committed = "the", separator = "")
        h.inputLogic.mEventDispatcher.disableRevert()
        backspace()
        assertEquals("th|", editorState())
    }

    // ── the body: no separator -> the word comes back COMPOSING ─────────────────

    @Test
    fun revertWithNoSeparator_restoresTheTypedWordAsComposingText() {
        afterAutoCorrection("the", 3, typed = "teh", committed = "the", separator = "")
        val ctx = backspace()

        assertEquals("teh|", editorState())
        assertTrue(
            "the restored word must be re-opened for editing, not committed",
            h.editor.hasComposingRegion(),
        )
        assertEquals("teh", h.editor.composingText)
        assertEquals("teh", h.inputLogic.mComposingTracker.composingText)
        assertTrue(ctx.shouldUpdateSuggestions())
    }

    @Test
    fun revertUnlearnsTheCorrectionAndClearsTheCommitRecord() {
        afterAutoCorrection("the", 3, typed = "teh", committed = "the", separator = "")
        backspace()
        // The correction the user rejected is removed from the dynamic model.
        verify(h.dictionaryLoader).unlearnWord("the")
        assertSame(
            "the record must go idle so a second backspace cannot revert again",
            CommitEventRecord.IDLE, h.inputLogic.mEventDispatcher,
        )
        assertFalse(h.inputLogic.mEventDispatcher.isRevertEligible())
    }

    @Test
    fun secondBackspaceAfterANoSeparatorRevert_shrinksTheRestoredWordByOne() {
        // The restored word is composing, so the next keystroke takes the composing path and
        // trims one character — it does NOT re-apply the correction and does NOT drop the word.
        afterAutoCorrection("the", 3, typed = "teh", committed = "the", separator = "")
        backspace()
        assertEquals("teh|", editorState())

        backspace()
        assertEquals("te|", editorState())
        assertTrue(h.editor.hasComposingRegion())
        assertEquals("te", h.inputLogic.mComposingTracker.composingText)
    }

    @Test
    fun thirdAndFourthBackspacesWalkTheRestoredWordToEmpty() {
        afterAutoCorrection("the", 3, typed = "teh", committed = "the", separator = "")
        backspace()
        repeat(3) { backspace() }
        assertEquals("|", editorState())
        assertFalse(h.inputLogic.mComposingTracker.isComposing)
        assertFalse(h.editor.hasComposingRegion())
    }

    // ── the body: separator present -> the word comes back COMMITTED ────────────

    @Test
    fun revertWithASeparator_restoresTheTypedWordAndTheSeparatorAsCommittedText() {
        afterAutoCorrection("the ", 4, typed = "teh", committed = "the", separator = " ")
        backspace(x = swipeDeleteRevert)

        assertEquals("teh |", editorState())
        assertFalse(
            "with the separator restored the word is committed, not re-opened",
            h.editor.hasComposingRegion(),
        )
        assertFalse(h.inputLogic.mComposingTracker.isComposing)
    }

    @Test
    fun secondBackspaceAfterASeparatorRevert_isAPlainCharacterDelete() {
        // Nothing is composing and the record is idle, so the next keystroke just eats the
        // restored separator one character at a time.
        afterAutoCorrection("the ", 4, typed = "teh", committed = "the", separator = " ")
        backspace(x = swipeDeleteRevert)
        assertEquals("teh |", editorState())

        backspace()
        assertEquals("teh|", editorState())
        backspace()
        assertEquals("te|", editorState())
    }

    @Test
    fun revertLeavesEarlierTextAlone() {
        // Only the corrected word and the whitespace/punctuation run after it are deleted.
        afterAutoCorrection("i think the ", 12, typed = "teh", committed = "the", separator = " ")
        backspace(x = swipeDeleteRevert)
        assertEquals("i think teh |", editorState())
    }

    @Test
    fun revertWithAPunctuationSeparator_restoresThePunctuationToo() {
        afterAutoCorrection("the. ", 5, typed = "teh", committed = "the", separator = ". ")
        backspace(x = swipeDeleteRevert)
        assertEquals("teh. |", editorState())
    }

    @Test
    fun revertWhenTheRecordedSeparatorIsNotActuallyThere_fallsBackToTheComposingForm() {
        // The `TextUtils.equals(str3, getTextBeforeCursor(length2, 0))` check: the record says a
        // space was typed, but the editor shows something else. The separator is then NOT
        // restored and the word comes back composing instead of committed.
        afterAutoCorrection("the!", 4, typed = "teh", committed = "the", separator = " ")
        backspace(x = swipeDeleteRevert)
        assertEquals("teh|", editorState())
        assertTrue(h.editor.hasComposingRegion())
    }

    // ── commitType 1 overrides the recorded separator ───────────────────────────

    @Test
    fun revertWithCommitTypeOne_usesPeriodSpaceAsTheSeparator() {
        // `if (1 == commitType) str3 = ". "` — the double-space-period substitution records its
        // own separator regardless of what the commit event said.
        afterAutoCorrection("the. ", 5, typed = "teh", committed = "the", separator = " ")
        backspace(x = swipeDeleteRevert, commitType = 1)
        assertEquals("teh. |", editorState())
        assertFalse(h.editor.hasComposingRegion())
    }

    // ── the auto-corrected composing word is committed before the revert ────────

    @Test
    fun backspaceOnAnAutoCorrectedComposingWord_commitsItFirst() {
        // `composing && wasAutoCorrected` is the one composing state that does NOT take the
        // shrink-by-one path: the word is committed first and the keystroke then continues
        // down the ordinary delete path.
        h.startSession("", 0)
        h.inputLogic.mRichInputConnection.setComposingText("the", 1)
        h.seedComposing("the")
        h.inputLogic.mComposingTracker.setWasAutoCorrected(true)
        h.editor.dropPendingSelectionEvents()

        backspace()

        assertFalse(h.inputLogic.mComposingTracker.isComposing)
        assertFalse(h.editor.hasComposingRegion())
        assertEquals("th|", editorState())
        verify(h.dictionaryLoader, never()).unlearnWord(anyString())
    }
}
