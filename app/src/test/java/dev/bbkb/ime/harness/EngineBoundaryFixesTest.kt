package dev.bbkb.ime.harness

import dev.bbkb.ime.core.engine.NuanceSDKManager
import dev.bbkb.ime.core.keyevent.InputSource
import dev.bbkb.ime.core.suggestion.SuggestedWords
import dev.bbkb.ime.core.textinput.composing.ComposingTextTracker
import com.blackberry.nuanceshim.NuanceSDK
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Executable proof for the engine-boundary and lifecycle findings closed in this pass
 * (docs/2026-07_pipeline-audit-findings_reference.md): `EB-1`, `EB-5`, `LC-8`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class EngineBoundaryFixesTest {

    private lateinit var h: PipelineHarness

    @Before fun setUp() { h = PipelineHarness() }
    @After fun tearDown() { h.close() }

    // ── EB-1: the gesturePending flag must not leak ──────────────────────────────

    @Test
    fun eb1_emptyBatchResult_stillConsumesGesturePending() {
        // THE defect. gesturePending is set on every accepted touchEnd and used to be consumed
        // only on the words.size() > 0 branch. A swipe that produced nothing — a garbage swipe, or
        // one in a field where predictions got disabled — therefore left the flag set forever, and
        // every later empty-composing request then skipped BOTH setContextBuffer and the bridge's
        // defensive clear(), silently dropping sentence context from next-word predictions until a
        // successful gesture or a process restart.
        h.enableStatefulGesturePending()
        NuanceSDKManager.noteGestureDeposit()

        h.uiHandler.postBatchInputSuggestions(SuggestedWords.EMPTY, InputSource.HARDWARE)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertFalse(
            "an empty gesture result must still consume the pending flag (EB-1)",
            NuanceSDKManager.isGesturePending(),
        )
    }

    // Coverage limit: the words.size() > 0 branch is NOT covered here. It calls
    // commitGestureSuggestion, which needs a real SettingsManager and KeyboardSwitcher, neither
    // of which this harness builds. The hoist is above the size check so both branches share the
    // consume, but only the empty branch is executable-tested.

    // ── EB-5: clearAll must reset ITS OWN engine, not the process singleton ──────

    @Test
    fun eb5_clearAll_resetsShiftOnTheInjectedEngineOnly() {
        // The spell-checker runs in this process on a tracker wrapping the SECONDARY engine.
        // clearAll() used to write shift state through NuanceSDKManager.getInstance() — the
        // PRIMARY engine — so every spell-check zeroed the IME's shift state from an IPC thread
        // while leaving the secondary's untouched.
        val secondary = mock(NuanceSDK::class.java)
        `when`(secondary.primaryLanguage).thenReturn(Locale.US)
        val primary = NuanceSDKManager.getInstance()!!   // the harness always stubs this

        val tracker = ComposingTextTracker(secondary)
        tracker.clearAll()

        verify(secondary).setShiftState(0)
        verify(primary, never()).setShiftState(0)
    }

    // ── LC-8: cursor-anchor monitoring must be requested per editor ──────────────

    @Test
    fun lc8_cursorUpdatesAreRequestedAgainstTheLiveConnection() {
        // NOTE ON WHAT THIS DOES AND DOES NOT PROVE. requestCursorUpdates was always issued
        // here, in resetInputState — the LC-8 defect is that the PKB start-input branch SKIPS
        // resetInputState entirely, so on a KEY2 no editor reached through that path ever got
        // cursor-anchor monitoring. The fix adds the call to that branch in
        // InputSessionCoordinator, which needs a real IME this harness cannot build. So this test
        // pins the invariant (a reset connection gets monitoring requested) and would catch its
        // removal, but it does NOT cover the PKB branch. That half stays device-verified only.
        h.startSession("hello", 5)
        h.editor.callLog.clear()

        h.inputLogic.resetInputState(null, h.settings)

        assertTrue(
            "the editor must be asked to monitor cursor anchors: ${h.editor.callLog}",
            h.editor.callLog.any { it.startsWith("requestCursorUpdates(") },
        )
    }
}
