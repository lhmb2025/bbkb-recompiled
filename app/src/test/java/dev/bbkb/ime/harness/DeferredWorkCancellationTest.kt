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
 * Executable proof for the "deferred work outlives its context" findings of the Phase 2 audit
 * (docs/2026-07_pipeline-audit-findings_reference.md): `DW-2`, `DW-5`, `DW-8`.
 *
 * All three were the same defect — a message that writes text or hides the keyboard, with no
 * `removeMessages` site anywhere, so it landed in whatever field was current when it finally
 * ran. All three were fixed by adding a line to `cancelPendingSuggestionUpdates()`. Until now
 * that fix was code-verified only: nothing failed if the line were deleted again.
 *
 * These tests close that gap. Each asserts the queue directly, so deleting any single
 * `removeMessages` line from `cancelPendingSuggestionUpdates()` fails exactly one test and
 * names the message that regressed.
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
    fun cancelPendingSuggestionUpdates_clearsEveryDeferredWriter() {
        h.queueAllCancelSetMessages()
        h.uiHandler.cancelPendingSuggestionUpdates()
        assertEquals(
            "cancelPendingSuggestionUpdates left deferred work queued: " + h.pendingMessages(),
            emptySet<String>(),
            h.pendingMessages(),
        )
    }

    @Test
    fun dw2_gestureCommitMessageIsCancellable() {
        // DW-2: MSG_BATCH_INPUT_SUGGESTIONS is the only message that COMMITS TEXT, and it had
        // no removeMessages site anywhere — a gesture committed into the field the user had
        // already left.
        assertCancelled(PipelineHarness.Msg.BATCH_INPUT_SUGGESTIONS, "MSG_BATCH_INPUT_SUGGESTIONS")
    }

    @Test
    fun dw5_voiceCommitMessageIsCancellable() {
        // DW-5: MSG_COMMIT_TEXT writes text into whatever editor is current at delivery.
        assertCancelled(PipelineHarness.Msg.COMMIT_TEXT, "MSG_COMMIT_TEXT")
    }

    @Test
    fun dw8_quickSwitchCancelMessageIsCancellable() {
        // DW-8: MSG_CANCEL_QUICK_SWITCH hides the keyboard on a decision that may be stale by
        // the time it lands.
        assertCancelled(PipelineHarness.Msg.CANCEL_QUICK_SWITCH, "MSG_CANCEL_QUICK_SWITCH")
    }

    // ── DW-4: multitap state and its timer surviving session teardown ────────────
    //
    // Coverage limit, stated so nobody reads more into these than they prove: they call
    // resetMultitapState() directly, so they prove the reset does its job — not that
    // cleanupKeyboard() calls it. That wiring is a private method on a real BlackBerryIME the
    // harness cannot build, and it stays device-verified only.

    @Test
    fun dw4_resetMultitapState_dropsStateAndTimerWithoutCommitting() {
        // Before the fix, nothing in any lifecycle path reset multitap state or removed
        // MSG_MULTITAP_TIMEOUT — removeMultitapTimeout's only callers were the two
        // commitMultitap methods. So both survived teardown, and pressing the same key in the
        // next field inside the leftover 750ms window hit the stale continuation branch, where
        // the shift-locked event it produced was dropped outright: the keystroke vanished.
        val handler = dev.bbkb.ime.core.keyevent.MultitapEventHandler(h.ime, STUB_LAYOUT)

        // Mid-multitap in field A: state armed, timeout queued.
        handler.onInputEvent(modifierPress('e'.code, android.view.KeyEvent.KEYCODE_E))
        assertTrue("multitap should be in progress", handler.isMultitapInProgress)
        assertTrue("timeout should be queued", h.uiHandler.hasMessages(MSG_MULTITAP_TIMEOUT))

        // Field switch teardown. commitMultitap() is deliberately NOT what runs here: it would
        // flush the in-progress character into whatever editor is current by then.
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
        // The user-visible half: the first press in the new field must be treated as a NEW
        // multitap (index -1 = base character), not as a continuation of field A's sequence.
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
            override fun getMoreKeysForKey(s: String?): Array<String> = arrayOf("è", "é")
            override fun getMoreKeysForKeyByStyle(s: String?): Array<String> = arrayOf("è", "é")
        }
    }

    /** Queue one message, cancel, and require that this specific message is what went away. */
    private fun assertCancelled(what: Int, name: String) {
        h.uiHandler.sendMessage(h.uiHandler.obtainMessage(what))
        assertTrue("$name was not queued — the test proves nothing", h.uiHandler.hasMessages(what))
        h.uiHandler.cancelPendingSuggestionUpdates()
        assertFalse(
            "$name survived cancelPendingSuggestionUpdates() — it is missing from the cancel set",
            h.uiHandler.hasMessages(what),
        )
    }
}
