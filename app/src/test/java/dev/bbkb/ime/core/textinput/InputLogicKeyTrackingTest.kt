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
 * Pins [InputLogic]'s hardware key-event tracking (genuine auto-repeats vs repeats delivered before
 * the IME had focus) and the gesture auto-commit callback ([InputLogic.onAutoCommitWord]).
 * [NuanceSDKManager] is static-mocked so construction does not touch the native engine.
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
        // The id must be a Long shift: with an Int `<< 32` it collapses to deviceId + keyCode, and
        // device 1 + KEYCODE_ENTER (66) == 67 == device 2 + KEYCODE_ENVELOPE (65).
        inputLogic.trackKeyEvent(keyEvent(KeyEvent.KEYCODE_ENTER, deviceId = 1))
        assertFalse(inputLogic.isKeyTracked(keyEvent(KeyEvent.KEYCODE_ENVELOPE, deviceId = 2)))
        assertFalse(inputLogic.untrackKeyEvent(keyEvent(KeyEvent.KEYCODE_ENVELOPE, deviceId = 2)))
        assertTrue(inputLogic.isKeyTracked(keyEvent(KeyEvent.KEYCODE_ENTER, deviceId = 1)))
    }

    @Test
    fun gestureAutoCommit_onMainThread_commitsWordToEditor() {
        // Arm the connection the way the IME lifecycle does.
        assertTrue(inputLogic.mRichInputConnection.resetConnection(0, 0, false))

        inputLogic.onAutoCommitWord("hello")

        verify(ic).commitText(argThat { s: CharSequence -> s.toString() == "hello" }, eq(1))
    }
}
