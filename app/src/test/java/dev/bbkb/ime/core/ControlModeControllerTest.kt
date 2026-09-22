package dev.bbkb.ime.core

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.bbkb.ime.core.ime.ControlModeController

/**
 * Pure state-machine tests for [ControlModeController]. The host is a recorder; no Android
 * objects are needed because the chord paths take primitive key data.
 */
class ControlModeControllerTest {

    private class FakeHost : ControlModeController.Host {
        override var isVkbControlModeEnabled = true
        override var controlModeSetting = 0
        val sent = mutableListOf<String>()
        var showCount = 0
        var hideCount = 0
        override fun sendKeyDownWithMeta(keyCode: Int, metaState: Int) { sent += "down:$keyCode/$metaState" }
        override fun sendKeyUpWithMeta(keyCode: Int, metaState: Int) { sent += "up:$keyCode/$metaState" }
        override fun showControlModeUi() { showCount++ }
        override fun hideControlModeUi() { hideCount++ }
    }

    private val ctrl = ControlModeController.META_CTRL_LEFT
    private val ctrlShift = ControlModeController.META_CTRL_LEFT or KeyEvent.META_SHIFT_ON

    @Test
    fun softShiftChordSendsCtrlLetterDownAndUp() {
        val host = FakeHost()
        val c = ControlModeController(host)
        assertFalse(c.handleSoftKeyDown(-3))          // shift down: not consumed, just latched
        assertTrue(c.isVkbShiftChordActive)
        assertTrue(c.handleSoftKeyDown('c'.code))     // Ctrl+C
        assertTrue(c.handleSoftKeyUp('c'.code))
        assertEquals(listOf("down:${KeyEvent.KEYCODE_C}/$ctrl", "up:${KeyEvent.KEYCODE_C}/$ctrl"), host.sent)
        assertTrue(c.handleSoftKeyUp(-3))             // shift-lock consumed the release
        assertFalse(c.isVkbShiftChordActive)
    }

    @Test
    fun softChordMapsDeleteEnterAndRedo() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.handleSoftKeyDown(-3)
        c.handleSoftKeyDown(-5)
        c.handleSoftKeyDown(10)
        c.handleSoftKeyDown('Y'.code)
        assertEquals(
            listOf("down:${KeyEvent.KEYCODE_DEL}/$ctrl", "down:${KeyEvent.KEYCODE_ENTER}/$ctrl", "down:${KeyEvent.KEYCODE_Z}/$ctrlShift"),
            host.sent
        )
    }

    @Test
    fun softShiftThenSymLatchesStickyModeUntilNextKey() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.handleSoftKeyDown(-3)
        assertTrue(c.handleSoftKeyDown(-34))
        assertTrue(c.handleSoftKeyUp(-34))
        assertTrue(c.handleSoftKeyUp(-3))             // latch
        assertEquals(1, host.showCount)
        assertFalse(c.isVkbShiftChordActive)          // sticky mode, not the shift chord
        assertTrue(c.handleSoftKeyDown('v'.code))     // still routes as a chord
        assertTrue(c.handleSoftKeyUp('v'.code))       // ...and the release clears the mode
        assertEquals(listOf("down:${KeyEvent.KEYCODE_V}/$ctrl", "up:${KeyEvent.KEYCODE_V}/$ctrl"), host.sent)
        assertTrue(host.hideCount >= 1)
        assertFalse(c.handleSoftKeyDown('a'.code))    // mode is gone: plain key
        assertEquals(2, host.sent.size)
    }

    @Test
    fun softControlModeDisabledIsTransparent() {
        val host = FakeHost().apply { isVkbControlModeEnabled = false }
        val c = ControlModeController(host)
        assertFalse(c.handleSoftKeyDown(-3))
        assertFalse(c.handleSoftKeyDown('c'.code))
        assertTrue(host.sent.isEmpty())
        assertEquals(0, host.hideCount)
    }

    @Test
    fun hardCtrlChordSendsAndReleaseEndsIt() {
        val host = FakeHost()
        val c = ControlModeController(host)
        assertTrue(c.handleHardKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, 0, false))
        assertTrue(c.handleHardKeyDown(KeyEvent.KEYCODE_V, 0, true))
        assertTrue(c.handleHardKeyUp(KeyEvent.KEYCODE_V, 0, true))
        assertTrue(c.handleHardKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, 0, false))
        assertEquals(listOf("down:${KeyEvent.KEYCODE_V}/$ctrl", "up:${KeyEvent.KEYCODE_V}/$ctrl"), host.sent)
        assertFalse(c.isInCtrlMode)
        assertEquals(0, host.showCount)
    }

    @Test
    fun hardCtrlHeldAloneUntilRepeatLatchesStickyMode() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.handleHardKeyDown(KeyEvent.KEYCODE_CTRL_RIGHT, 0, false)
        c.handleHardKeyDown(KeyEvent.KEYCODE_CTRL_RIGHT, 1, false)
        c.handleHardKeyDown(KeyEvent.KEYCODE_CTRL_RIGHT, 2, false)
        assertTrue(c.handleHardKeyUp(KeyEvent.KEYCODE_CTRL_RIGHT, 0, false))
        assertTrue(c.isInCtrlMode)
        assertEquals(1, host.showCount)
        // Next printing key chords and ends the mode; space would also end it.
        assertTrue(c.handleHardKeyDown(KeyEvent.KEYCODE_Y, 0, true))
        assertTrue(c.handleHardKeyUp(KeyEvent.KEYCODE_Y, 0, true))
        assertEquals(listOf("down:${KeyEvent.KEYCODE_Z}/$ctrlShift", "up:${KeyEvent.KEYCODE_Z}/$ctrlShift"), host.sent)
        assertFalse(c.isInCtrlMode)
    }

    @Test
    fun hardSpaceCancelsCtrlMode() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.toggleCtrlMode()
        assertTrue(c.isInCtrlMode)
        assertEquals(1, host.showCount)
        assertTrue(c.handleHardKeyDown(KeyEvent.KEYCODE_SPACE, 0, false))
        assertFalse(c.isInCtrlMode)
        assertTrue(host.sent.isEmpty())
    }

    @Test
    fun toggleTwiceShowsThenHides() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.toggleCtrlMode()
        c.toggleCtrlMode()
        assertEquals(1, host.showCount)
        assertEquals(1, host.hideCount)
        assertFalse(c.isInCtrlMode)
    }

    @Test
    fun resetAllDropsStateWithoutTouchingUi() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.toggleCtrlMode()
        c.resetAll()
        assertFalse(c.isInCtrlMode)
        assertEquals(0, host.hideCount)
    }

    @Test
    fun keyCodeForCharsMatchAndroidKeycodes() {
        assertEquals(KeyEvent.KEYCODE_A, ControlModeController.keyCodeForChar('a'.code))
        assertEquals(KeyEvent.KEYCODE_Z, ControlModeController.keyCodeForChar('Z'.code))
        assertEquals(KeyEvent.KEYCODE_Q, ControlModeController.keyCodeForChar('q'.code))
    }
}
