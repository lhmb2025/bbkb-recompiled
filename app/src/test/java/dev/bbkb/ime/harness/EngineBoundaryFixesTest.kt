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

/** Engine-boundary and lifecycle invariants: gesture-pending consume, per-engine shift reset, cursor monitoring. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class EngineBoundaryFixesTest {

    private lateinit var h: PipelineHarness

    @Before fun setUp() { h = PipelineHarness() }
    @After fun tearDown() { h.close() }

    @Test
    fun eb1_emptyBatchResult_stillConsumesGesturePending() {
        // A swipe with no words must still consume gesturePending; a stuck flag makes every
        // empty-composing request skip setContextBuffer and the bridge's clear(), dropping context.
        h.enableStatefulGesturePending()
        NuanceSDKManager.noteGestureDeposit()

        h.uiHandler.postBatchInputSuggestions(SuggestedWords.EMPTY, InputSource.HARDWARE)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertFalse(
            "an empty gesture result must still consume the pending flag (EB-1)",
            NuanceSDKManager.isGesturePending(),
        )
    }

    // Coverage limit: the words.size() > 0 branch needs a real SettingsManager and
    // KeyboardSwitcher; the consume sits above the size check so both branches share it.

    @Test
    fun eb5_clearAll_resetsShiftOnTheInjectedEngineOnly() {
        // The spell checker's tracker wraps the secondary engine; clearAll() must reset shift
        // there, not on the IME's primary engine (from an IPC thread).
        val secondary = mock(NuanceSDK::class.java)
        `when`(secondary.primaryLanguage).thenReturn(Locale.US)
        val primary = NuanceSDKManager.getInstance()!!   // the harness always stubs this

        val tracker = ComposingTextTracker(secondary)
        tracker.clearAll()

        verify(secondary).setShiftState(0)
        verify(primary, never()).setShiftState(0)
    }

    @Test
    fun lc8_cursorUpdatesAreRequestedAgainstTheLiveConnection() {
        // Pins only that resetInputState requests cursor updates. The PKB start-input branch skips
        // resetInputState and makes its own call in InputSessionCoordinator, not testable here.
        h.startSession("hello", 5)
        h.editor.callLog.clear()

        h.inputLogic.resetInputState(null, h.settings)

        assertTrue(
            "the editor must be asked to monitor cursor anchors: ${h.editor.callLog}",
            h.editor.callLog.any { it.startsWith("requestCursorUpdates(") },
        )
    }
}
