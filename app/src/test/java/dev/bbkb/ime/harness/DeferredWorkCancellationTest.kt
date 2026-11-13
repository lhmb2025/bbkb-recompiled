package dev.bbkb.ime.harness

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the teardown cancel sets: text-writing messages are cleared by `cancelPendingTextCommits()`
 * (view finish/start), never by `cancelPendingSuggestionUpdates()`, which every suggestion update
 * runs. Each test asserts the queue, so dropping one `removeMessages` line fails one test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class DeferredWorkCancellationTest {

    private lateinit var h: PipelineHarness

    @Before fun setUp() { h = PipelineHarness() }
    @After fun tearDown() { h.close() }

    @Test
    fun harnessObservesQueuedMessages() {
        // Guards the tests below: if the paused-looper assumption ever broke, the messages
        // would drain and every cancellation test would pass vacuously.
        assertEquals(emptySet<String>(), h.pendingMessages())
        h.queueAllCancelSetMessages()
        assertEquals(PipelineHarness.Msg.CANCEL_SET.values.toSet(), h.pendingMessages())
    }

    @Test
    fun teardownCancels_clearEveryDeferredWriter() {
        h.queueAllCancelSetMessages()
        h.uiHandler.cancelPendingSuggestionUpdates()
        h.uiHandler.cancelPendingTextCommits()
        assertEquals(
            "teardown left deferred work queued: " + h.pendingMessages(),
            emptySet<String>(),
            h.pendingMessages(),
        )
    }

    @Test
    fun aSuggestionUpdateLeavesQueuedTextCommitsAlone() {
        // cancelPendingSuggestionUpdates runs at the start of every MSG_UPDATE_SUGGESTIONS, so
        // it must not drop a queued dictation result or swipe word.
        h.queueAllCancelSetMessages()
        h.uiHandler.cancelPendingSuggestionUpdates()
        assertEquals(
            PipelineHarness.Msg.TEXT_COMMIT_SET.values.toSet(),
            h.pendingMessages(),
        )
    }

    @Test
    fun aQueuedCommitSurvivesASuggestionUpdateAndIsDelivered() {
        h.uiHandler.postCommitText("dictated")
        h.uiHandler.postUpdateSuggestions(1)
        // What the MSG_UPDATE_SUGGESTIONS branch does first.
        h.uiHandler.cancelPendingSuggestionUpdates()
        assertTrue(
            "MSG_COMMIT_TEXT was dropped by a suggestion update",
            h.uiHandler.hasMessages(PipelineHarness.Msg.COMMIT_TEXT),
        )
        assertFalse(h.uiHandler.hasMessages(PipelineHarness.Msg.UPDATE_SUGGESTIONS))
    }

    @Test
    fun finishingTheInputViewDropsQueuedTextCommits() {
        h.queueAllCancelSetMessages()
        h.uiHandler.handleFinishInputView(false)
        assertTrue(
            "a text commit survived the view finishing: " + h.pendingMessages(),
            h.pendingMessages().intersect(PipelineHarness.Msg.TEXT_COMMIT_SET.values.toSet()).isEmpty(),
        )
    }

    @Test
    fun showingASuggestionReplyDoesNotCancelTheNextKeystrokesUpdate() {
        // showAndRefreshForSlideboard runs cancelPendingSuggestionUpdates while a reply is shown;
        // an update queued by then is for newer text and must survive.
        org.mockito.Mockito.doAnswer {
            h.uiHandler.cancelPendingSuggestionUpdates()
            null
        }.`when`(h.ime).showSuggestionStrip(anyWords())
        h.uiHandler.postUpdateSuggestions(1)

        h.uiHandler.handleMessage(
            h.uiHandler.obtainMessage(
                PipelineHarness.Msg.SHOW_SUGGESTIONS, 0, h.inputLogic.getSessionGeneration(),
                dev.bbkb.ime.core.suggestion.SuggestedWords.EMPTY,
            ),
        )

        org.mockito.Mockito.verify(h.ime).showSuggestionStrip(anyWords())
        assertTrue(
            "the delivery cancelled a newer update request",
            h.uiHandler.hasMessages(PipelineHarness.Msg.UPDATE_SUGGESTIONS),
        )
        // Outside a delivery the cancel works as before.
        h.uiHandler.cancelPendingSuggestionUpdates()
        assertFalse(h.uiHandler.hasMessages(PipelineHarness.Msg.UPDATE_SUGGESTIONS))
    }

    @Test
    fun dw2_gestureCommitMessageIsCancellable() {
        // MSG_BATCH_INPUT_SUGGESTIONS commits a gesture word; uncancelled, it lands in the next field.
        assertCommitCancelled(PipelineHarness.Msg.BATCH_INPUT_SUGGESTIONS, "MSG_BATCH_INPUT_SUGGESTIONS")
    }

    @Test
    fun dw5_voiceCommitMessageIsCancellable() {
        // MSG_COMMIT_TEXT writes text into whatever editor is current at delivery.
        assertCommitCancelled(PipelineHarness.Msg.COMMIT_TEXT, "MSG_COMMIT_TEXT")
    }

    // Coverage limit: the multitap tests call resetMultitapState() directly; that
    // cleanupKeyboard() calls it is not provable here (needs a real BlackBerryIME).

    @Test
    fun dw4_resetMultitapState_dropsStateAndTimerWithoutCommitting() {
        // Leftover state plus a live 750ms timeout would send the next field's same-key press
        // down the stale continuation branch, which drops the keystroke.
        val handler = dev.bbkb.ime.core.keyevent.MultitapEventHandler(h.ime, STUB_LAYOUT)

        // Mid-multitap in field A: state armed, timeout queued.
        handler.onInputEvent(modifierPress('e'.code, android.view.KeyEvent.KEYCODE_E))
        assertTrue("multitap should be in progress", handler.isMultitapInProgress)
        assertTrue("timeout should be queued", h.uiHandler.hasMessages(MSG_MULTITAP_TIMEOUT))

        // Teardown resets rather than commitMultitap(), which would flush into the next editor.
        handler.resetMultitapState()

        assertFalse("multitap state survived teardown", handler.isMultitapInProgress)
        assertFalse(
            "MSG_MULTITAP_TIMEOUT survived teardown — it will fire into the next field",
            h.uiHandler.hasMessages(MSG_MULTITAP_TIMEOUT),
        )
        assertEquals("teardown must not commit text into the next editor", "", h.editor.getText())
    }

    @Test
    fun dw4_afterReset_sameKeyStartsFreshRatherThanContinuing() {
        // The first press in the new field starts fresh (index -1 = base character).
        val handler = dev.bbkb.ime.core.keyevent.MultitapEventHandler(h.ime, STUB_LAYOUT)
        handler.onInputEvent(modifierPress('e'.code, android.view.KeyEvent.KEYCODE_E))
        handler.resetMultitapState()

        handler.onInputEvent(modifierPress('e'.code, android.view.KeyEvent.KEYCODE_E))
        assertEquals(
            "same key after teardown continued the old sequence instead of restarting",
            -1,
            multitapIndexOf(handler),
        )
    }

    private fun modifierPress(codePoint: Int, keyCode: Int) =
        dev.bbkb.ime.core.keyevent.InputEvent.createModifierKeyEvent(
            codePoint, keyCode, null, false, false, 0L,
        )

    private fun multitapIndexOf(handler: dev.bbkb.ime.core.keyevent.MultitapEventHandler): Int =
        dev.bbkb.ime.core.keyevent.MultitapEventHandler::class.java
            .getDeclaredField("multitapIndex")
            .apply { isAccessible = true }
            .getInt(handler)

    private companion object {
        /** MSG_MULTITAP_TIMEOUT in UIUpdateHandler (private there). */
        const val MSG_MULTITAP_TIMEOUT = 10

        /** Two accents for the base char, so the continuation branch has somewhere to go. */
        val STUB_LAYOUT = object : dev.bbkb.ime.core.KeyboardLayoutCallback {
            override fun getKeyLabelForKeyEvent(e: android.view.KeyEvent?): String = ""
            override fun getMoreKeysForKey(baseCharacter: String?): Array<String> = arrayOf("è", "é")
            override fun getMoreKeysForKeyByStyle(baseCharacter: String?): Array<String> = arrayOf("è", "é")
        }
    }

    /** Mockito's any() is null, which a Kotlin non-null parameter rejects; the matcher still records. */
    private fun anyWords(): dev.bbkb.ime.core.suggestion.SuggestedWords =
        org.mockito.ArgumentMatchers.any(dev.bbkb.ime.core.suggestion.SuggestedWords::class.java)
            ?: dev.bbkb.ime.core.suggestion.SuggestedWords.EMPTY

    /** Queue one message, cancel, and require that this specific message is what went away. */
    private fun assertCommitCancelled(what: Int, name: String) {
        h.uiHandler.sendMessage(h.uiHandler.obtainMessage(what))
        assertTrue("$name was not queued — the test proves nothing", h.uiHandler.hasMessages(what))
        h.uiHandler.cancelPendingTextCommits()
        assertFalse(
            "$name survived cancelPendingTextCommits() — it is missing from the cancel set",
            h.uiHandler.hasMessages(what),
        )
    }
}
