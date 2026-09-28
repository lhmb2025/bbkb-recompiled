package dev.bbkb.ime.core.textinput

import dev.bbkb.ime.core.SymbolPageProvider
import dev.bbkb.ime.core.device.state.PhysicalKeyboardStateTracker
import dev.bbkb.ime.core.engine.Dictionary
import dev.bbkb.ime.core.keyevent.InputEvent
import dev.bbkb.ime.core.keyevent.InputEventContext
import dev.bbkb.ime.core.keyevent.InputSource
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.suggestion.SuggestedWords
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
 * Scenarios for `InputLogic.handleSwipeDelete`, the path every "delete a word" gesture must
 * take — the VKB swipe, voice input's delete, and (since the fix these tests were written for)
 * the CKB flick/swipe-left slot in `CkbGestureBridge`.
 *
 * The bridge used to delete the word itself with `deleteSurroundingText(wordLength)`. That is
 * the one thing a word delete must never do while a composing region is live: the editor keeps
 * the composing text and deletes AROUND it, so after a suggestion was flicked in and the space
 * behind it backspaced away (which re-opens the word as composing), each gesture removed five
 * characters BEFORE "swipe" while "swipe" stayed put. The first scenario replays exactly that.
 *
 * Like [BackspaceScenariosTest], everything here runs the real controllers over a
 * [dev.bbkb.ime.harness.FakeEditor] that reproduces the editor's composing-region contract, and
 * asserts what reached the editor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class SwipeDeleteScenariosTest {

    private lateinit var h: PipelineHarness
    private lateinit var subtypeStatic: MockedStatic<SubtypeManager>
    private lateinit var settingsStatic: MockedStatic<SettingsManager>

    @Before
    fun setUp() {
        h = PipelineHarness()
        // isCjkLocale() and CommitEventRecord.isRevertEligible() reach SubtypeManager.getInstance().
        subtypeStatic = mockStatic(SubtypeManager::class.java)
        val subtypes = mock(SubtypeManager::class.java)
        `when`(subtypes.currentSubtypeLocale).thenReturn(Locale.US)
        subtypeStatic.`when`<SubtypeManager> { SubtypeManager.getInstance() }.thenReturn(subtypes)

        val sm = mock(SettingsManager::class.java)
        `when`(sm.getSettingsValues()).thenReturn(h.settings)
        settingsStatic = mockStatic(SettingsManager::class.java)
        settingsStatic.`when`<SettingsManager> { SettingsManager.getInstance() }.thenReturn(sm)

        // The manual pick ends by clearing the manual shift through the state tracker.
        `when`(h.ime.getPhysicalKeyboardStateTracker())
            .thenReturn(mock(PhysicalKeyboardStateTracker::class.java))
    }

    @After
    fun tearDown() {
        settingsStatic.close()
        subtypeStatic.close()
        h.close()
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    /** "text|cursor" rendering of the editor, so a failure message shows the whole state. */
    private fun editorState(): String {
        val t = h.editor.text
        val c = h.editor.selStart
        val e = h.editor.selEnd
        val marker = if (c == e) "|" else "|..$e.."
        return t.substring(0, c) + marker + t.substring(c)
    }

    /** Arm a live composing word after [at], exactly as typing it would have. */
    private fun armComposing(typed: String, at: String) {
        h.startSession(at, at.length)
        h.inputLogic.mRichInputConnection.setComposingText(typed, 1)
        h.seedComposing(typed)
        h.editor.dropPendingSelectionEvents()
    }

    private fun suggestions(vararg words: String): SuggestedWords {
        val list = ArrayList<SuggestedWords.SuggestedWordInfo>()
        for ((i, w) in words.withIndex()) {
            list.add(SuggestedWords.SuggestedWordInfo(w, 100 - i, 0, Dictionary.DICTIONARY_USER_TYPED, -1, -1, null))
        }
        return SuggestedWords(list, false, false, 0)
    }

    /** Pick suggestion [slot] the way the flick-up gesture does (`onSuggestionPicked` → `handleManualPick`). */
    private fun pickSuggestion(slot: Int) {
        h.inputLogic.handleManualPick(
            h.settings, h.inputLogic.mCurrentSuggestions.getWordInfo(slot),
            mock(SymbolPageProvider::class.java), 0, h.uiHandler, InputSource.SOFTWARE,
        )
        h.editor.dropPendingSelectionEvents()
    }

    /** One ordinary (non-repeat) hardware backspace through the real controller. */
    private fun backspace() {
        val noCoords = -4
        val event = InputEvent.createKeyPress(-1, -5, noCoords, noCoords, 1000L, false)
        val ctx = InputEventContext(h.settings, event, 1000L, 0, 0)
        ctx.setInputSource(InputSource.HARDWARE)
        h.inputLogic.mBackspaceController.handleBackspace(event, ctx, h.settings, 0)
        h.editor.dropPendingSelectionEvents()
    }

    /**
     * Re-open the word ending at the cursor as a composing region, in the editor AND the
     * tracker — what `RecorrectionController.performRecorrection` does after a backspace lands
     * the cursor on the end of a committed word (setComposingFromCodePoints + setComposingRegion).
     */
    private fun reopenWordBeforeCursor(word: String) {
        val end = h.editor.selStart
        val start = end - word.length
        assertEquals("the word to reopen must sit right before the cursor", word, h.editor.text.substring(start, end))
        h.seedComposing(word)
        h.inputLogic.mRichInputConnection.setComposingRegion(start, end)
        h.editor.dropPendingSelectionEvents()
    }

    private fun swipeDelete(): Boolean = h.inputLogic.handleSwipeDelete(h.settings)

    // ── the reported bug ─────────────────────────────────────────────────────────

    @Test
    fun swipeDelete_afterAPickedWordIsReopenedByBackspace_deletesThatWordNotTheOneBeforeIt() {
        // (1) flick up commits "swipe" plus its space
        armComposing("swip", at = "When typing and selecting a word from ")
        h.inputLogic.mCurrentSuggestions = suggestions("swipe", "swiped", "swipes")
        pickSuggestion(0)
        assertEquals("When typing and selecting a word from swipe |", editorState())

        // (2) backspace takes the space back, and the word is re-opened as composing
        backspace()
        assertEquals("When typing and selecting a word from swipe|", editorState())
        reopenWordBeforeCursor("swipe")
        assertTrue(h.editor.hasComposingRegion())
        assertEquals("swipe", h.editor.composingText)
        assertTrue(h.inputLogic.mComposingTracker.isComposing)

        // (3) swipe to delete: the composing word goes, the word before it stays
        assertTrue(swipeDelete())

        assertEquals("When typing and selecting a word from |", editorState())
        assertFalse("the region must be closed, not left open on nothing", h.editor.hasComposingRegion())
        assertFalse(h.inputLogic.mComposingTracker.isComposing)
    }

    @Test
    fun swipeDelete_overAComposingWord_neverTouchesTheTextBeforeTheRegion() {
        // The editor contract that made the bug: deleteSurroundingText leaves a live composing
        // region alone and deletes around it. The path must close the region before deleting.
        armComposing("swipe", at = "a word from ")

        assertTrue(swipeDelete())

        assertEquals("a word from |", editorState())
        assertFalse(h.editor.hasComposingRegion())
        assertFalse(h.inputLogic.mComposingTracker.isComposing)
    }

    // ── the ordinary cases the gesture inherits ──────────────────────────────────

    @Test
    fun swipeDelete_withNothingComposing_deletesThePreviousWordAndTheWhitespaceAfterIt() {
        h.startSession("a word from swipe ", 18)

        assertTrue(swipeDelete())

        assertEquals("a word from |", editorState())
    }

    @Test
    fun swipeDelete_withNothingComposing_stopsAtTheWordBoundary() {
        h.startSession("a word from swipe", 17)

        assertTrue(swipeDelete())

        assertEquals("a word from |", editorState())
        assertTrue(swipeDelete())
        assertEquals("a word |", editorState())
    }

    @Test
    fun swipeDelete_atTheStartOfTheField_deletesNothing() {
        h.startSession("", 0)

        assertFalse(swipeDelete())

        assertEquals("|", editorState())
    }

    @Test
    fun swipeDelete_withASelection_deletesTheSelection() {
        h.startSession("a word from swipe", 17)
        h.editor.appSetSelection(7, 12)
        h.editor.dropPendingSelectionEvents()
        h.inputLogic.mRichInputConnection.resetConnection(7, 12, false)

        assertTrue(swipeDelete())

        assertEquals("a word |swipe", editorState())
    }
}
