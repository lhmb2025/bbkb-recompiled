package dev.bbkb.ime.harness

import dev.bbkb.ime.core.textinput.InputLogic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Composing invariants and stale-reply scenarios replayed through the real [InputLogic],
 * RichInputConnection and ComposingTextTracker against a [FakeEditor].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class TextPipelineScenariosTest {

    private lateinit var h: PipelineHarness

    @Before fun setUp() { h = PipelineHarness() }
    @After fun tearDown() { h.close() }

    @Test
    fun fakeEditor_setComposingWithNoRegion_inserts() {
        // With no composing region, setComposingText inserts at the cursor.
        h.editor.appSetText("instagram", 9, 9)
        assertFalse(h.editor.hasComposingRegion())
        h.editor.setComposingText("instagram", 1)
        assertEquals("instagraminstagram", h.editor.getText())
    }

    @Test
    fun fakeEditor_commitText_replacesComposingRegion() {
        h.editor.setComposingText("hel", 1)
        assertEquals("hel", h.editor.getText())
        h.editor.setComposingText("hello", 1)
        assertEquals("hello", h.editor.getText())
        h.editor.commitText("hello", 1)
        assertEquals("hello", h.editor.getText())
        assertFalse(h.editor.hasComposingRegion())
    }

    @Test
    fun fakeEditor_deliversAsyncSelectionEvents() {
        h.editor.setComposingText("hi", 1)
        val e = h.editor.pollSelectionEvent()
        requireNotNull(e)
        assertEquals(0, e.oldSelStart)
        assertEquals(2, e.newSelStart)
    }

    @Test
    fun ric_finishThenInvalidate_leavesCursorUnknown() {
        h.startSession("instagram", 9)
        h.inputLogic.mRichInputConnection.setComposingText("instagram", 1)
        h.inputLogic.mRichInputConnection.finishComposingText()
        h.inputLogic.mRichInputConnection.invalidateConnection()
        assertEquals(-1, h.inputLogic.mRichInputConnection.cursorStart)
    }

    @Test
    fun launcherEnter_suggestionReplyAfterFinish_doesNotDuplicate() {
        // Post-Enter state on a search field: editor holds the word with no region, RIC cursor
        // unknown, but the tracker still holds the word and a suggestion reply is in flight.
        h.startSession("instagram", 9)
        h.seedComposing("instagram")
        h.inputLogic.mRichInputConnection.finishComposingText()
        h.inputLogic.mRichInputConnection.invalidateConnection()
        assertTrue(h.inputLogic.mComposingTracker.isComposing)
        assertEquals(-1, h.inputLogic.mRichInputConnection.cursorStart)

        // The queued worker reply lands after the search already ran.
        h.deliverSuggestions(listOf("instagram"))

        // The cursor-unknown guard drops the reply; without it the region-less editor would
        // get a second copy ("instagraminstagram").
        assertEquals("instagram", h.editor.getText())
        h.fourCopies().assertConsistent()
    }

    @Test
    fun suggestionReplyWhileComposing_liveConnection_updatesRegionNotDuplicate() {
        // Live connection with a region: the reply refreshes the region in place.
        h.startSession()
        h.editor.setComposingText("instagram", 1)
        h.inputLogic.mRichInputConnection.resetConnection(9, 9, false)
        h.seedComposing("instagram")
        h.deliverSuggestions(listOf("instagram"))
        assertEquals("instagram", h.editor.getText())
    }

    @Test
    fun fieldSwitch_clearingTracker_preventsLeak() {
        // Field A: user typed "Hello" (composing, uncommitted).
        h.startSession()
        h.editor.setComposingText("Hello", 1)
        h.seedComposing("Hello")
        assertTrue(h.inputLogic.mComposingTracker.isComposing)

        // Field switch teardown clears composing state.
        h.inputLogic.mComposingTracker.clearAll()
        h.inputLogic.mRichInputConnection.finishComposingText()

        // Field B starts empty; the tracker must not carry "Hello" into it.
        h.startSession("", 0)
        assertFalse(
            "composing tracker leaked across field switch (BUG5)",
            h.inputLogic.mComposingTracker.isComposing,
        )
        assertEquals("", h.editor.getText())
    }

    @Test
    fun staleSuggestionReply_afterTeardown_doesNotStampAutoCorrectOnEmptyTracker() {
        // A worker reply for field A's word landing after teardown must not stamp its
        // auto-correction onto the empty tracker, or field B's next separator commits it.
        h.startSession()
        h.editor.setComposingText("teh", 1)
        h.seedComposing("teh")
        // index 0 is the typed word, index 1 the correction (getMinSuggestionsIndex() is 1).
        h.deliverSuggestions(listOf("teh", "the"), willAutoCorrect = true)
        assertEquals("the", h.inputLogic.mComposingTracker.autoCorrection)

        // Teardown: field A is gone, tracker holds nothing.
        h.inputLogic.mComposingTracker.clearAll()
        h.inputLogic.mRichInputConnection.finishComposingText()
        h.startSession("", 0)
        assertFalse(h.inputLogic.mComposingTracker.isComposing)

        // The in-flight reply for field A's "teh" finally arrives.
        h.deliverSuggestions(listOf("teh", "the"), willAutoCorrect = true)

        assertNull(
            "stale reply stamped field A's auto-correction onto an empty tracker (DW-3)",
            h.inputLogic.mComposingTracker.autoCorrection,
        )
        assertNull(
            "stale reply stamped field A's picked suggestion onto an empty tracker (DW-3)",
            h.inputLogic.mComposingTracker.pickedSuggestion,
        )
    }

    @Test
    fun suggestionReply_whileComposing_stillAppliesAutoCorrection() {
        // The stale-reply guard must not starve the normal live-typing path.
        h.startSession()
        h.editor.setComposingText("recieve", 1)
        h.seedComposing("recieve")
        h.deliverSuggestions(listOf("recieve", "receive"), willAutoCorrect = true)
        assertEquals("receive", h.inputLogic.mComposingTracker.autoCorrection)
        assertNotNull(h.inputLogic.mComposingTracker.pickedSuggestion)
    }

    @Test
    fun autoCommitPostedFromWorker_afterSessionTeardown_doesNotCommitIntoNextField() {
        // The auto-commit callback posts the gesture word from a native thread; a field switch
        // before delivery must cancel it rather than commit into the next editor.
        h.startSession("", 0)

        // Simulate the native callback landing off the main thread.
        val worker = Thread { h.inputLogic.onAutoCommitWord("hello") }
        worker.start()
        worker.join()

        // Session teardown happens before the posted runnable is drained.
        h.inputLogic.cancelInput()

        // Field B.
        h.startSession("", 0)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertEquals(
            "gesture word from the previous session committed into the next field (DW-1)",
            "", h.editor.getText(),
        )
    }

    @Test
    fun sameEditor_distinguishesTwoFieldsWithIdenticalTypeAndOptions() {
        // equivalentEditorInfo is true for any two fields sharing inputType/imeOptions; the
        // 800 ms orientation swallow needs sameEditor to tell them apart.
        val a = android.view.inputmethod.EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            fieldId = 101; packageName = "com.example.app"
        }
        val b = android.view.inputmethod.EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            fieldId = 202; packageName = "com.example.app"
        }
        assertTrue(
            "the two fields are equivalent — that is exactly why the old check was too weak",
            dev.bbkb.ime.keyboard.internal.KeyboardId.equivalentEditorInfo(a, b),
        )
        assertFalse(
            "sameEditor must not conflate two different fields (LC-2)",
            dev.bbkb.ime.keyboard.internal.KeyboardId.sameEditor(a, b),
        )
        assertTrue(
            "sameEditor must still recognise the same field across a rotation restart",
            dev.bbkb.ime.keyboard.internal.KeyboardId.sameEditor(a, a),
        )
    }

    @Test
    fun commitWithCursorSkip_cacheTracksEditorCursor() {
        // A manual pick commits with newCursorPosition=2 to step over a following separator;
        // the RIC cursor cache must land where the editor's cursor does.
        h.startSession("hi ", 2)          // "hi| ": a space already follows the cursor
        h.editor.setComposingText("hi", 1)
        h.seedComposing("hi")

        h.inputLogic.mRichInputConnection.commitText("hey", 2)

        assertEquals(
            "RIC cursor cache disagrees with the editor after a cursor-skip commit (SS-3)",
            h.editor.getSelStart(), h.inputLogic.mRichInputConnection.cursorStart,
        )
    }
}
