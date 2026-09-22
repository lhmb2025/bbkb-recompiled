package dev.bbkb.ime.core.textinput

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.engine.NuanceSDKManager
import dev.bbkb.ime.core.engine.DictionaryLoader
import dev.bbkb.ime.keyboard.auxbar.suggestions.SuggestionStripListener
import com.blackberry.nuanceshim.NuanceSDK
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [InputLogic]'s hardware key-event tracking (the guard that separates
 * genuine auto-repeats from spurious repeats delivered before the IME had focus) and the
 * NuanceSDK gesture auto-commit callback ([InputLogic.onAutoCommitWord]).
 *
 * [NuanceSDKManager] is static-mocked so construction doesn't touch the native engine;
 * the host IME is a Mockito mock (inline mock-maker handles the final Kotlin class), and
 * the editor is a scripted [InputConnection] like in RichInputConnectionTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class InputLogicKeyTrackingTest {

    private lateinit var sdkStatic: MockedStatic<NuanceSDKManager>
    private lateinit var ime: BlackBerryIME
    private lateinit var ic: InputConnection
    private lateinit var inputLogic: InputLogic

    @Before
    fun setUp() {
        sdkStatic = mockStatic(NuanceSDKManager::class.java)
        sdkStatic.`when`<NuanceSDK> { NuanceSDKManager.getInstance() }
            .thenReturn(mock(NuanceSDK::class.java))

        ime = mock(BlackBerryIME::class.java)
        ic = mock(InputConnection::class.java)
        `when`(ime.currentInputConnection).thenReturn(ic)
        `when`(ic.getTextBeforeCursor(anyInt(), anyInt())).thenReturn("")
        `when`(ic.getTextAfterCursor(anyInt(), anyInt())).thenReturn("")

        inputLogic = InputLogic(
            ime,
            mock(SuggestionStripListener::class.java),
            mock(DictionaryLoader::class.java)
        )
    }

    @After
    fun tearDown() {
        sdkStatic.close()
    }

    private fun keyEvent(keyCode: Int, repeat: Int = 0, deviceId: Int = 1): KeyEvent =
        KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, repeat, 0, deviceId, 0)

    // ── key-event tracking ───────────────────────────────────────────────────

    @Test
    fun trackedKey_isReportedTracked_untilUntracked() {
        val e = keyEvent(KeyEvent.KEYCODE_A)
        assertFalse(inputLogic.isKeyTracked(e))
        inputLogic.trackKeyEvent(e)
        assertTrue(inputLogic.isKeyTracked(e))
        assertTrue(inputLogic.untrackKeyEvent(e))
        assertFalse(inputLogic.isKeyTracked(e))
    }

    @Test
    fun untrackWithoutTrack_returnsFalse() {
        assertFalse(inputLogic.untrackKeyEvent(keyEvent(KeyEvent.KEYCODE_B)))
    }

    @Test
    fun repeatOfTrackedKey_isTrackedRepeat() {
        inputLogic.trackKeyEvent(keyEvent(KeyEvent.KEYCODE_A))
        val repeat = keyEvent(KeyEvent.KEYCODE_A, repeat = 1)
        assertTrue(inputLogic.isTrackedKeyRepeat(repeat))
        assertFalse(inputLogic.isUntrackedKeyRepeat(repeat))
    }

    @Test
    fun repeatOfUntrackedKey_isUntrackedRepeat() {
        // Key held before the IME gained focus: repeats arrive with no preceding key-down.
        val repeat = keyEvent(KeyEvent.KEYCODE_A, repeat = 1)
        assertFalse(inputLogic.isTrackedKeyRepeat(repeat))
        assertTrue(inputLogic.isUntrackedKeyRepeat(repeat))
    }

    @Test
    fun nonRepeatEvent_isNeverARepeat() {
        inputLogic.trackKeyEvent(keyEvent(KeyEvent.KEYCODE_A))
        val down = keyEvent(KeyEvent.KEYCODE_A, repeat = 0)
        assertFalse(inputLogic.isTrackedKeyRepeat(down))
        assertFalse(inputLogic.isUntrackedKeyRepeat(down))
    }

    @Test
    fun sameKeyCodeOnDifferentDevices_isTrackedIndependently() {
        // The tracking id is (deviceId << 32) + keyCode: PKB and CKB share key codes.
        inputLogic.trackKeyEvent(keyEvent(KeyEvent.KEYCODE_A, deviceId = 1))
        assertFalse(inputLogic.isKeyTracked(keyEvent(KeyEvent.KEYCODE_A, deviceId = 2)))
        assertTrue(inputLogic.isKeyTracked(keyEvent(KeyEvent.KEYCODE_A, deviceId = 1)))
    }

    @Test
    fun deviceIdAndKeyCodeDoNotAlias() {
        // Audit TI-4: getKeyEventId was `(getDeviceId() << 32) + keyCode`, and getDeviceId()
        // returns an Int, so `<< 32` shifted by 32 % 32 == 0 and the id was just
        // deviceId + keyCode. Device 1 / keycode 66 then collided with device 2 / keycode 65 —
        // a key-up on one device untracked the other device's held key. The pair above does not
        // expose it because the two ids differ by the device id alone; this pair does.
        // device 1 + KEYCODE_ENTER (66) == 67 == device 2 + KEYCODE_ENVELOPE (65) under the old id.
        inputLogic.trackKeyEvent(keyEvent(KeyEvent.KEYCODE_ENTER, deviceId = 1))
        assertFalse(inputLogic.isKeyTracked(keyEvent(KeyEvent.KEYCODE_ENVELOPE, deviceId = 2)))
        assertFalse(inputLogic.untrackKeyEvent(keyEvent(KeyEvent.KEYCODE_ENVELOPE, deviceId = 2)))
        assertTrue(inputLogic.isKeyTracked(keyEvent(KeyEvent.KEYCODE_ENTER, deviceId = 1)))
    }

    // ── NuanceSDK gesture auto-commit callback ───────────────────────────────

    @Test
    fun gestureAutoCommit_onMainThread_commitsWordToEditor() {
        // Arm the connection the way the IME lifecycle does.
        assertTrue(inputLogic.mRichInputConnection.resetConnection(0, 0, false))

        inputLogic.onAutoCommitWord("hello")

        verify(ic).commitText(argThat { s: CharSequence -> s.toString() == "hello" }, eq(1))
    }
}
