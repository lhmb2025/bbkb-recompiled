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
        override var isAltActiveForCharacter = false
        override var isCtrlDoubleTapLockEnabled = true
        override var ctrlDoubleTapTimeoutMs = 600L
        override var isPkbAltActiveForChord = false
        override var isPkbShiftActiveForChord = false
        override var isVkbShiftActiveForChord = false
        val sent = mutableListOf<String>()
        var showCount = 0
        var hideCount = 0
        var ctrlChanges = 0
        var label = false
        var labelChanges = 0
        var pkbConsumed = 0
        var vkbShiftConsumed = 0
        override fun sendKeyDownWithMeta(keyCode: Int, metaState: Int) { sent += "down:$keyCode/$metaState" }
        override fun sendKeyUpWithMeta(keyCode: Int, metaState: Int) { sent += "up:$keyCode/$metaState" }
        override fun showControlModeUi() { showCount++ }
        override fun hideControlModeUi() { hideCount++ }
        override fun onPkbCtrlStateChanged() { ctrlChanges++ }
        override fun setVkbCtrlLabel(active: Boolean) { label = active; labelChanges++ }
        override fun consumePkbModifiersAfterChord(keyCode: Int) { pkbConsumed++ }
        override fun consumeVkbShiftAfterChord() { vkbShiftConsumed++ }
    }

    private val ctrl = ControlModeController.META_CTRL_LEFT
    private val ctrlShift = ControlModeController.META_CTRL_LEFT or KeyEvent.META_SHIFT_ON
    /** Ctrl plus a Shift the user put on: META_SHIFT_ON | META_SHIFT_LEFT_ON. */
    private val ctrlUserShift = ctrl or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
    private val CTRL = KeyEvent.KEYCODE_CTRL_LEFT

    private fun ControlModeController.tapCtrl(at: Long) {
        handleHardKeyDown(CTRL, 0, false, at)
        handleHardKeyUp(CTRL, 0, false)
    }

    private fun ControlModeController.typeHard(keyCode: Int, printing: Boolean = true): Pair<Boolean, Boolean> =
        Pair(handleHardKeyDown(keyCode, 0, printing, 0L), handleHardKeyUp(keyCode, 0, printing))

    private fun chord(keyCode: Int, meta: Int) = listOf("down:$keyCode/$meta", "up:$keyCode/$meta")

    // ================================================================== VKB

    @Test
    fun softHeldToSymbolChordSendsCtrlLetterDownAndUp() {
        val host = FakeHost()
        val c = ControlModeController(host)
        assertFalse(c.handleSoftKeyDown(-3))          // ?123 down: not consumed, just held
        assertTrue(c.isVkbShiftChordActive)
        assertTrue(c.handleSoftKeyDown('c'.code))     // Ctrl+C
        assertTrue(c.handleSoftKeyUp('c'.code))
        assertEquals(chord(KeyEvent.KEYCODE_C, ctrl), host.sent)
        assertTrue(c.handleSoftKeyUp(-3))             // a ?123 used as Ctrl: its release is consumed
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
    fun softLongPressLatchesCtrlForTheNextKeyOnly() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.handleSoftKeyDown(-3)
        assertTrue(c.handleSoftKeyDown(-34))
        assertTrue(c.handleSoftKeyUp(-34))
        assertTrue(c.handleSoftKeyUp(-3))             // latch
        assertEquals(1, host.showCount)
        assertFalse(c.isVkbShiftChordActive)          // latched, not held
        assertTrue(c.handleSoftKeyDown('v'.code))     // still routes as a chord
        assertTrue(c.handleSoftKeyUp('v'.code))       // ...and the release clears the latch
        assertEquals(chord(KeyEvent.KEYCODE_V, ctrl), host.sent)
        assertTrue(host.hideCount >= 1)
        assertFalse(c.handleSoftKeyDown('a'.code))    // latch is gone: plain key
        assertEquals(2, host.sent.size)
    }

    @Test
    fun softControlModeDisabledIsTransparent() {
        val host = FakeHost().apply { isVkbControlModeEnabled = false }
        val c = ControlModeController(host)
        assertFalse(c.handleSoftKeyDown(-3))
        assertFalse(c.handleSoftKeyDown('c'.code))
        assertFalse(c.suppressesSoftCodeInput('c'.code))
        assertTrue(host.sent.isEmpty())
        assertEquals(0, host.hideCount)
    }

    /**
     * The reported bug: Ctrl+A selected everything and then the "a" was typed over it. The IME
     * asks [ControlModeController.suppressesSoftCodeInput] between the press (which sent the
     * chord) and the release; it must say yes for the letter, Enter and Delete, and only then.
     */
    @Test
    fun softChordLetterIsNotAlsoTyped() {
        val c = ControlModeController(FakeHost())
        assertFalse(c.suppressesSoftCodeInput('a'.code))
        c.handleSoftKeyDown(-3)
        assertFalse("?123 itself is still processed", c.suppressesSoftCodeInput(-3))
        c.handleSoftKeyDown('a'.code)                 // press: Ctrl+A goes out
        assertTrue(c.suppressesSoftCodeInput('a'.code))   // code input: not typed
        assertTrue(c.suppressesSoftCodeInput(10))
        assertTrue(c.suppressesSoftCodeInput(-5))
        assertFalse(c.suppressesSoftCodeInput(-1))
        c.handleSoftKeyUp('a'.code)
        c.handleSoftKeyUp(-3)
        assertFalse(c.suppressesSoftCodeInput('a'.code))
    }

    @Test
    fun softLatchedChordLetterIsNotAlsoTyped() {
        val c = ControlModeController(FakeHost())
        c.handleSoftKeyDown(-3); c.handleSoftKeyDown(-34); c.handleSoftKeyUp(-34); c.handleSoftKeyUp(-3)
        c.handleSoftKeyDown('v'.code)
        assertTrue(c.suppressesSoftCodeInput('v'.code))
        c.handleSoftKeyUp('v'.code)
        assertFalse("the latch was for one key", c.suppressesSoftCodeInput('v'.code))
    }

    @Test
    fun softQuestion123ReadsCtrlFromTheLongPressUntilTheLatchIsSpent() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.handleSoftKeyDown(-3)
        assertFalse(host.label)
        c.handleSoftKeyDown(-34)
        assertTrue("armed as soon as the long-press fires", host.label)
        c.handleSoftKeyUp(-34)
        c.handleSoftKeyUp(-3)
        assertTrue("latched", host.label)
        c.handleSoftKeyDown('z'.code)
        c.handleSoftKeyUp('z'.code)
        assertFalse(host.label)
        assertEquals(2, host.labelChanges)
    }

    @Test
    fun softChordWhileHeldAfterTheLongPressDoesNotLatchAndClearsTheLabel() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.handleSoftKeyDown(-3); c.handleSoftKeyDown(-34); c.handleSoftKeyUp(-34)
        c.handleSoftKeyDown('c'.code); c.handleSoftKeyUp('c'.code)
        assertTrue(c.handleSoftKeyUp(-3))
        assertFalse(host.label)
        assertFalse(c.handleSoftKeyDown('a'.code))
    }

    /**
     * A tap on the key while it reads CTRL turns Ctrl off and does nothing else: the press, the
     * code input and the release are all swallowed, so the keyboard does not also switch to the
     * symbols (owner report, KEY2, 2026-10-14).
     */
    @Test
    fun softTapOnCtrlOnlyDropsTheLatch() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.handleSoftKeyDown(-3); c.handleSoftKeyDown(-34); c.handleSoftKeyUp(-34); c.handleSoftKeyUp(-3)
        assertTrue(host.label)
        assertTrue("press swallowed", c.handleSoftKeyDown(-3))
        assertFalse(host.label)
        assertTrue("code input swallowed", c.suppressesSoftCodeInput(-3))
        assertTrue("release swallowed: no switch to the symbols", c.handleSoftKeyUp(-3))
        assertFalse(c.handleSoftKeyDown('a'.code))
        c.handleSoftKeyUp('a'.code)
        assertTrue(host.sent.isEmpty())
    }

    @Test
    fun softQuestion123TapsNormallyAgainAfterCtrlWasTurnedOff() {
        val c = ControlModeController(FakeHost())
        c.handleSoftKeyDown(-3); c.handleSoftKeyDown(-34); c.handleSoftKeyUp(-34); c.handleSoftKeyUp(-3)
        c.handleSoftKeyDown(-3); c.handleSoftKeyUp(-3)          // CTRL tap: off
        assertFalse(c.handleSoftKeyDown(-3))                     // a plain ?123 tap goes through
        assertFalse(c.suppressesSoftCodeInput(-3))
        assertFalse(c.handleSoftKeyUp(-3))
    }

    @Test
    fun softHoldingCtrlToTurnItOffDoesNotReArmIt() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.handleSoftKeyDown(-3); c.handleSoftKeyDown(-34); c.handleSoftKeyUp(-34); c.handleSoftKeyUp(-3)
        c.handleSoftKeyDown(-3)                                  // turns it off...
        assertTrue(c.handleSoftKeyDown(-34))                     // ...and its long-press fires
        c.handleSoftKeyUp(-34)
        c.handleSoftKeyUp(-3)
        assertFalse(host.label)
        assertFalse(c.handleSoftKeyDown('a'.code))
    }

    @Test
    fun softShiftAddsToTheChordInsteadOfCancellingIt() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.handleSoftKeyDown(-3); c.handleSoftKeyDown(-34); c.handleSoftKeyUp(-34); c.handleSoftKeyUp(-3)
        assertFalse(c.handleSoftKeyDown(-1))          // Shift goes to the keyboard as usual...
        assertFalse(c.handleSoftKeyUp(-1))
        assertTrue(host.label)                        // ...and Ctrl is still latched
        host.isVkbShiftActiveForChord = true
        c.handleSoftKeyDown('Z'.code)
        // The board unshifts on release; the key reports its unshifted code then.
        assertTrue(c.handleSoftKeyUp('z'.code))
        assertEquals(chord(KeyEvent.KEYCODE_Z, ctrlUserShift), host.sent)
        assertEquals(1, host.vkbShiftConsumed)
    }

    @Test
    fun softChordWithoutShiftDoesNotTouchTheBoardsShift() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.handleSoftKeyDown(-3)
        c.handleSoftKeyDown('c'.code); c.handleSoftKeyUp('c'.code)
        assertEquals(0, host.vkbShiftConsumed)
    }

    // ================================================================== PKB

    @Test
    fun hardCtrlHeldWithAKeySendsTheChordAndReleaseEndsIt() {
        val host = FakeHost()
        val c = ControlModeController(host)
        assertTrue(c.handleHardKeyDown(CTRL, 0, false, 0L))
        assertTrue(c.isCtrlActive)
        assertEquals(Pair(true, true), c.typeHard(KeyEvent.KEYCODE_V))
        assertTrue(c.handleHardKeyUp(CTRL, 0, false))
        assertEquals(chord(KeyEvent.KEYCODE_V, ctrl), host.sent)
        assertFalse(c.isCtrlActive)
        assertEquals(0, host.showCount)
        assertEquals(1, host.pkbConsumed)
    }

    @Test
    fun hardCtrlHeldChordsEveryKeyUntilReleased() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.handleHardKeyDown(CTRL, 0, false, 0L)
        c.typeHard(KeyEvent.KEYCODE_C)
        c.typeHard(KeyEvent.KEYCODE_V)
        c.handleHardKeyUp(CTRL, 0, false)
        assertEquals(chord(KeyEvent.KEYCODE_C, ctrl) + chord(KeyEvent.KEYCODE_V, ctrl), host.sent)
        assertFalse(c.typeHard(KeyEvent.KEYCODE_A).first)
    }

    @Test
    fun hardCtrlTapIsOnForTheNextKeyOnly() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.tapCtrl(at = 0L)
        assertTrue(c.isInCtrlMode)
        assertFalse(c.isCtrlLocked)
        assertEquals(1, host.showCount)
        assertEquals(Pair(true, true), c.typeHard(KeyEvent.KEYCODE_Y))
        assertEquals(chord(KeyEvent.KEYCODE_Z, ctrlShift), host.sent)
        assertFalse(c.isCtrlActive)
        assertEquals(Pair(false, false), c.typeHard(KeyEvent.KEYCODE_A))
    }

    @Test
    fun hardCtrlHeldAloneDoesNotLock() {
        val c = ControlModeController(FakeHost())
        c.handleHardKeyDown(CTRL, 0, false, 0L)
        for (r in 1..20) assertTrue(c.handleHardKeyDown(CTRL, r, false, r * 50L))
        c.handleHardKeyUp(CTRL, 0, false)
        assertFalse(c.isCtrlLocked)
        assertTrue("released without a key: on for the next key", c.isInCtrlMode)
    }

    @Test
    fun hardCtrlDoubleTapLocksAndATapUnlocks() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.tapCtrl(at = 1_000L)
        c.tapCtrl(at = 1_300L)
        assertTrue(c.isCtrlLocked)
        c.typeHard(KeyEvent.KEYCODE_C)
        c.typeHard(KeyEvent.KEYCODE_V)
        assertTrue("a lock outlives the keys it chords", c.isCtrlLocked)
        assertEquals(chord(KeyEvent.KEYCODE_C, ctrl) + chord(KeyEvent.KEYCODE_V, ctrl), host.sent)
        c.tapCtrl(at = 5_000L)
        assertFalse(c.isCtrlActive)
        assertFalse(c.typeHard(KeyEvent.KEYCODE_A).first)
    }

    @Test
    fun hardCtrlDoubleTapWithTheLockSettingOffTurnsCtrlOff() {
        val host = FakeHost().apply { isCtrlDoubleTapLockEnabled = false }
        val c = ControlModeController(host)
        c.tapCtrl(at = 1_000L)
        c.tapCtrl(at = 1_300L)
        assertFalse(c.isCtrlLocked)
        assertFalse(c.isCtrlActive)
    }

    @Test
    fun hardCtrlSecondTapOutsideTheWindowTurnsItOff() {
        val c = ControlModeController(FakeHost())
        c.tapCtrl(at = 1_000L)
        c.tapCtrl(at = 1_700L)
        assertFalse(c.isCtrlActive)
        assertFalse(c.isCtrlLocked)
    }

    @Test
    fun hardCtrlTripleTapLocksThenStartsOver() {
        val c = ControlModeController(FakeHost())
        c.tapCtrl(at = 1_000L)
        c.tapCtrl(at = 1_200L)
        assertTrue(c.isCtrlLocked)
        c.tapCtrl(at = 1_400L)
        assertFalse("a tap on a lock turns it off, however soon", c.isCtrlActive)
        c.tapCtrl(at = 1_600L)
        assertTrue("and the next tap starts again", c.isInCtrlMode)
        assertFalse(c.isCtrlLocked)
    }

    @Test
    fun hardCtrlHeldOnALockChordsAndTheReleaseUnlocks() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.tapCtrl(at = 0L); c.tapCtrl(at = 100L)
        c.handleHardKeyDown(CTRL, 0, false, 5_000L)
        c.typeHard(KeyEvent.KEYCODE_X)
        c.handleHardKeyUp(CTRL, 0, false)
        assertEquals(chord(KeyEvent.KEYCODE_X, ctrl), host.sent)
        assertFalse(c.isCtrlActive)
    }

    @Test
    fun hardChordAfterADoubleTapIsNotATap() {
        // Tap, then press-and-chord inside the window: the chord is the press's whole intent.
        val c = ControlModeController(FakeHost())
        c.tapCtrl(at = 0L)
        c.handleHardKeyDown(CTRL, 0, false, 200L)
        c.typeHard(KeyEvent.KEYCODE_C)
        c.handleHardKeyUp(CTRL, 0, false)
        assertFalse(c.isCtrlActive)
    }

    @Test
    fun hardShiftAddsToTheChord() {
        val host = FakeHost().apply { isPkbShiftActiveForChord = true }
        val c = ControlModeController(host)
        c.tapCtrl(at = 0L)
        c.typeHard(KeyEvent.KEYCODE_Z)
        assertEquals(chord(KeyEvent.KEYCODE_Z, ctrlUserShift), host.sent)
        assertEquals("the chord spends a tapped Shift like a typed key", 1, host.pkbConsumed)
    }

    @Test
    fun hardChordKeyUpMatchesItsDownEvenIfShiftChangedInBetween() {
        val host = FakeHost().apply { isPkbShiftActiveForChord = true }
        val c = ControlModeController(host)
        c.handleHardKeyDown(CTRL, 0, false, 0L)
        c.handleHardKeyDown(KeyEvent.KEYCODE_V, 0, true, 0L)
        host.isPkbShiftActiveForChord = false
        c.handleHardKeyUp(KeyEvent.KEYCODE_V, 0, true)
        assertEquals(chord(KeyEvent.KEYCODE_V, ctrlUserShift), host.sent)
    }

    @Test
    fun hardModifierKeysPassThroughWithoutCancellingCtrl() {
        val c = ControlModeController(FakeHost())
        c.tapCtrl(at = 0L)
        for (k in intArrayOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
                KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_SYM)) {
            assertEquals(Pair(false, false), c.typeHard(k, printing = false))
        }
        assertTrue(c.isInCtrlMode)
    }

    @Test
    fun hardAltOnTopOfALatchedCtrlTypesTheAltCharacterAndDropsCtrl() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.tapCtrl(at = 0L); c.tapCtrl(at = 100L)
        host.isPkbAltActiveForChord = true
        assertEquals(Pair(false, false), c.typeHard(KeyEvent.KEYCODE_Q))
        assertFalse(c.isCtrlActive)
        assertTrue(host.sent.isEmpty())
    }

    @Test
    fun hardSpaceTurnsCtrlOffAndIsSwallowed() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.tapCtrl(at = 0L); c.tapCtrl(at = 100L)
        assertEquals(Pair(true, true), c.typeHard(KeyEvent.KEYCODE_SPACE, printing = false))
        assertFalse(c.isCtrlActive)
        assertTrue(host.sent.isEmpty())
    }

    @Test
    fun hardOtherKeysPassThroughSpendingAOneShotButNotALock() {
        val c = ControlModeController(FakeHost())
        c.tapCtrl(at = 0L)
        assertEquals(Pair(false, false), c.typeHard(KeyEvent.KEYCODE_DPAD_LEFT, printing = false))
        assertFalse(c.isCtrlActive)
        c.tapCtrl(at = 5_000L); c.tapCtrl(at = 5_100L)
        c.typeHard(KeyEvent.KEYCODE_DPAD_LEFT, printing = false)
        assertTrue(c.isCtrlLocked)
    }

    @Test
    fun hardChordRepeatsAreSwallowedAndTheReleaseSentOnce() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.handleHardKeyDown(CTRL, 0, false, 0L)
        c.handleHardKeyDown(KeyEvent.KEYCODE_Z, 0, true, 0L)
        assertTrue(c.handleHardKeyDown(KeyEvent.KEYCODE_Z, 1, true, 0L))
        assertTrue(c.handleHardKeyDown(KeyEvent.KEYCODE_Z, 2, true, 0L))
        c.handleHardKeyUp(KeyEvent.KEYCODE_Z, 0, true)
        assertEquals(chord(KeyEvent.KEYCODE_Z, ctrl), host.sent)
    }

    @Test
    fun hardKeysPressedBeforeCtrlKeepTheirOwnRelease() {
        val c = ControlModeController(FakeHost())
        assertFalse(c.handleHardKeyDown(KeyEvent.KEYCODE_A, 0, true, 0L))
        c.tapCtrl(at = 0L)
        assertFalse("not a chord, not ours", c.handleHardKeyUp(KeyEvent.KEYCODE_A, 0, true))
    }

    @Test
    fun hardCtrlChangesAreReportedOncePerChange() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.handleHardKeyDown(CTRL, 0, false, 0L)      // off -> held
        c.handleHardKeyDown(CTRL, 1, false, 0L)      // repeat: nothing
        c.handleHardKeyUp(CTRL, 0, false)            // held -> one-shot: still active, no change
        c.handleHardKeyDown(CTRL, 0, false, 100L)    // second press: still active
        c.handleHardKeyUp(CTRL, 0, false)            // -> locked
        c.typeHard(KeyEvent.KEYCODE_C)               // a lock stays: nothing
        c.tapCtrl(at = 5_000L)                       // press: locked -> held; release: -> off
        assertEquals(4, host.ctrlChanges)
    }

    @Test
    fun shortcutTapsAreCtrlTaps() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.tapCtrlFromShortcut(1_000L)
        assertTrue(c.isInCtrlMode)
        assertFalse(c.isCtrlLocked)
        c.tapCtrlFromShortcut(1_250L)
        assertTrue(c.isCtrlLocked)
        c.tapCtrlFromShortcut(9_000L)
        assertFalse(c.isCtrlActive)
    }

    @Test
    fun clearControlStateDropsTheLatchAndShowsThenHides() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.tapCtrl(at = 0L)
        assertEquals(1, host.showCount)
        c.clearControlState()
        assertFalse(c.isInCtrlMode)
        assertTrue(host.hideCount >= 1)
    }

    @Test
    fun resetAllDropsStateWithoutTouchingUi() {
        val host = FakeHost()
        val c = ControlModeController(host)
        c.tapCtrl(at = 0L); c.tapCtrl(at = 100L)
        val hides = host.hideCount
        c.resetAll()
        assertFalse(c.isCtrlActive)
        assertEquals(hides, host.hideCount)
    }

    @Test
    fun keyCodeForCharsMatchAndroidKeycodes() {
        assertEquals(KeyEvent.KEYCODE_A, ControlModeController.keyCodeForChar('a'.code))
        assertEquals(KeyEvent.KEYCODE_Z, ControlModeController.keyCodeForChar('Z'.code))
        assertEquals(KeyEvent.KEYCODE_Q, ControlModeController.keyCodeForChar('q'.code))
    }
}
