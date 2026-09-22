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
 * Regression harness scenario suite — Phase 1 of the Text Pipeline Quality Campaign
 * (docs/2026-07_text-pipeline-quality-campaign_plan.md).
 *
 * Each test replays a real historical bug (or a core composing invariant) through the REAL
 * [InputLogic] / RichInputConnection / ComposingTextTracker against a [FakeEditor]. The suite
 * is the executable form of the 2026-05 manual regression net: any change to the pipeline that
 * reintroduces one of these bugs fails here, in `./gradlew testDebugUnitTest`, before it ships.
 *
 * The must-pass proof that the harness catches real defects is
 * [launcherEnter_suggestionReplyAfterFinish_doesNotDuplicate]: reverting the 2026-07-24
 * cursor-unknown guard in InputLogic.onSuggestionsReceived makes exactly that test fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class TextPipelineScenariosTest {

    private lateinit var h: PipelineHarness

    @Before fun setUp() { h = PipelineHarness() }
    @After fun tearDown() { h.close() }

    // ── FakeEditor semantics (the editor contract the bugs depended on) ──────────

    @Test
    fun fakeEditor_setComposingWithNoRegion_inserts() {
        // The InputConnection contract, and the launcher bug's mechanism: with no composing
        // region, setComposingText inserts at the cursor rather than replacing anything.
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

    // ── RichInputConnection ⟷ editor consistency ────────────────────────────────

    @Test
    fun ric_finishThenInvalidate_leavesCursorUnknown() {
        h.startSession("instagram", 9)
        h.inputLogic.mRichInputConnection.setComposingText("instagram", 1)
        h.inputLogic.mRichInputConnection.finishComposingText()
        h.inputLogic.mRichInputConnection.invalidateConnection()
        assertEquals(-1, h.inputLogic.mRichInputConnection.cursorStart)
    }

    // ── Bug: launcher-search Enter duplication (2026-07-24) ──────────────────────

    @Test
    fun launcherEnter_suggestionReplyAfterFinish_doesNotDuplicate() {
        // Reproduce the exact post-Enter state on a search field:
        //  - the editor holds the finalized word with NO composing region
        //    (finishComposingText ran during the editor-action handoff),
        //  - the RIC connection was invalidated (cursor unknown),
        //  - BUT the composing tracker was left alive with the word (the handoff never
        //    cleared it), and a 10 ms-delayed suggestion update was already queued.
        h.startSession("instagram", 9)
        h.seedComposing("instagram")
        h.inputLogic.mRichInputConnection.finishComposingText()
        h.inputLogic.mRichInputConnection.invalidateConnection()
        assertTrue(h.inputLogic.mComposingTracker.isComposing)
        assertEquals(-1, h.inputLogic.mRichInputConnection.cursorStart)

        // The queued worker reply lands after the search already ran.
        h.deliverSuggestions(listOf("instagram"))

        // With the cursor-unknown guard, the reply is dropped and the editor keeps one copy.
        // Without it, onSuggestionsReceived re-sends the word via setComposingText into a
        // region-less editor and the field becomes "instagraminstagram".
        assertEquals("instagram", h.editor.getText())
        h.fourCopies().assertConsistent()
    }

    @Test
    fun suggestionReplyWhileComposing_liveConnection_updatesRegionNotDuplicate() {
        // The healthy counterpart: connection live, editor still holds the composing region,
        // a suggestion reply refreshes the region in place (no duplication).
        h.startSession()
        h.editor.setComposingText("instagram", 1)
        h.inputLogic.mRichInputConnection.resetConnection(9, 9, false)
        h.seedComposing("instagram")
        h.deliverSuggestions(listOf("instagram"))
        assertEquals("instagram", h.editor.getText())
    }

    // ── Bug: composing leak across field switch (BUG5) ───────────────────────────

    @Test
    fun fieldSwitch_clearingTracker_preventsLeak() {
        // Field A: user typed "Hello" (composing, uncommitted).
        h.startSession()
        h.editor.setComposingText("Hello", 1)
        h.seedComposing("Hello")
        assertTrue(h.inputLogic.mComposingTracker.isComposing)

        // Field switch: the fix clears composing state. Model the post-fix teardown.
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

    // ── Phase 3: audit-finding regressions ──────────────────────────────────────

    @Test
    fun staleSuggestionReply_afterTeardown_doesNotStampAutoCorrectOnEmptyTracker() {
        // Audit DW-3 (tracker half). A worker reply for field A's word can land after the
        // session for field A is gone. The tracker writes used to run above the isComposing
        // guard, so field A's auto-correct candidate got stamped onto a tracker holding no
        // word — and the next separator in field B could commit it.
        h.startSession()
        h.editor.setComposingText("teh", 1)
        h.seedComposing("teh")
        // index 0 is the typed word, index 1 the correction — getMinSuggestionsIndex() is 1.
        h.deliverSuggestions(listOf("teh", "the"), willAutoCorrect = true)
        assertEquals("the", h.inputLogic.mComposingTracker.autoCorrection)

        // Teardown: field A is gone, tracker holds nothing.
        h.inputLogic.mComposingTracker.clearAll()
        h.inputLogic.mRichInputConnection.finishComposingText()
        h.startSession("", 0)
        assertFalse(h.inputLogic.mComposingTracker.isComposing)

        // The in-flight reply for field A's "teh" finally arrives.
        // index 0 is the typed word, index 1 the correction — getMinSuggestionsIndex() is 1.
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
        // The other side of DW-3: the guard must not starve the normal live-typing path.
        // This is the assertion the EB-2 revert taught us to write — a fix that quiets a
        // stale-data bug by also quieting the working case is a regression, not a fix.
        h.startSession()
        h.editor.setComposingText("recieve", 1)
        h.seedComposing("recieve")
        h.deliverSuggestions(listOf("recieve", "receive"), willAutoCorrect = true)
        assertEquals("receive", h.inputLogic.mComposingTracker.autoCorrection)
        assertNotNull(h.inputLogic.mComposingTracker.pickedSuggestion)
    }

    @Test
    fun autoCommitPostedFromWorker_afterSessionTeardown_doesNotCommitIntoNextField() {
        // Audit DW-1. The Nuance auto-commit callback arrives on a native thread and posts the
        // gesture word to the main handler. Before the fix that post was uncancellable and
        // unguarded, so a field switch between callback and delivery committed the word into
        // whatever editor was current by then.
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
        // Audit LC-2. The 800 ms orientation swallow keyed on equivalentEditorInfo, which is
        // true for ANY two fields sharing inputType/imeOptions — so a genuine switch between
        // two similar fields inside the window was discarded. sameEditor must separate them.
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
        // Audit SS-3. The manual-pick path commits with newCursorPosition=2 to step over a
        // separator already sitting after the cursor. The RIC cache used to advance as if the
        // cursor always landed right after the committed text, so copy 2 trailed copy 1 by one
        // character until onUpdateSelection healed it — and word-range/recorrection reads in
        // between saw the wrong position.
        h.startSession("hi ", 2)          // "hi| " — a space already follows the cursor
        h.editor.setComposingText("hi", 1)
        h.seedComposing("hi")

        h.inputLogic.mRichInputConnection.commitText("hey", 2)

        assertEquals(
            "RIC cursor cache disagrees with the editor after a cursor-skip commit (SS-3)",
            h.editor.getSelStart(), h.inputLogic.mRichInputConnection.cursorStart,
        )
    }
}
