package dev.bbkb.ime.core.device.config.builder

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The capture state machine, driven by synthetic key events — the only way to test it, since its
 * real input is a handset keyboard nobody has in CI.
 *
 * Each case here is a failure mode seen on real hardware rather than an invented one: a board key
 * whose key-up the accessibility service swallows, a key the phone does not have, two bezel keys
 * the firmware gave the same scancode, and the Back key, which on a PKB handset is a physical key
 * that would otherwise be recorded as whatever step happened to be open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GuidedCaptureTest {

    private fun GuidedCapture.press(scanCode: Int, keyCode: Int, deviceId: Int = 4): Boolean {
        val used = onKeyDown(scanCode, keyCode, deviceId, 0, 1_000L)
        onKeyUp(scanCode, keyCode, deviceId)
        return used
    }

    @Test
    fun walksTheStepsInOrderAndRecordsTheRawPair() {
        val capture = GuidedCapture()
        assertEquals(CaptureStep.SYM, capture.current())

        capture.press(249, KeyEvent.KEYCODE_SYM)

        assertEquals(CaptureStep.ALT, capture.current())
        val sym = capture.resultFor(CaptureStep.SYM)!!
        assertEquals(249, sym.scanCode)
        assertEquals(KeyEvent.KEYCODE_SYM, sym.keyCode)
        assertEquals(4, sym.deviceId)
        assertFalse(sym.skipped)
        assertTrue(sym.isUsable)
    }

    @Test
    fun aStepIsOnlyAnsweredOnTheKeyUpThatClosesIt() {
        val capture = GuidedCapture()
        capture.onKeyDown(249, KeyEvent.KEYCODE_SYM, 4, 0, 1_000L)

        assertEquals("the step stays open while the key is held", CaptureStep.SYM, capture.current())
        assertTrue(capture.isKeyHeld)
        assertNull(capture.resultFor(CaptureStep.SYM))

        capture.onKeyUp(249, KeyEvent.KEYCODE_SYM, 4)
        assertEquals(CaptureStep.ALT, capture.current())
    }

    @Test
    fun holdingAKeyMeasuresTheCadenceBetweenRepeatsOnly() {
        val capture = GuidedCapture()
        capture.onKeyDown(14, KeyEvent.KEYCODE_DEL, 4, 0, 1_000L)
        // The first repeat comes after the ROM's initial delay (400ms here), which is a different
        // number from the cadence and must not be averaged into it.
        capture.onKeyDown(14, KeyEvent.KEYCODE_DEL, 4, 1, 1_400L)
        capture.onKeyDown(14, KeyEvent.KEYCODE_DEL, 4, 2, 1_450L)
        capture.onKeyDown(14, KeyEvent.KEYCODE_DEL, 4, 3, 1_500L)
        capture.onKeyUp(14, KeyEvent.KEYCODE_DEL, 4)

        val captured = capture.resultFor(CaptureStep.SYM)!!
        assertEquals(50, captured.repeatIntervalMs)
        assertEquals(3, captured.repeatCount)
        assertTrue(captured.hasRepeatCadence())
    }

    @Test
    fun aTappedKeyHasNoCadence() {
        val capture = GuidedCapture()
        capture.press(249, KeyEvent.KEYCODE_SYM)
        val captured = capture.resultFor(CaptureStep.SYM)!!
        assertEquals(CapturedKey.NO_REPEAT, captured.repeatIntervalMs)
        assertFalse(captured.hasRepeatCadence())
    }

    @Test
    fun aDifferentKeysPressClosesAStepWhoseKeyUpNeverArrived() {
        // The MP01's Sym key: the accessibility service consumes the press AND the release, so the
        // capture can see a down with no matching up. The next key's down has to move it along.
        val capture = GuidedCapture()
        capture.onKeyDown(249, KeyEvent.KEYCODE_SYM, 4, 0, 1_000L)
        capture.onKeyDown(56, KeyEvent.KEYCODE_ALT_LEFT, 4, 0, 2_000L)

        assertEquals(249, capture.resultFor(CaptureStep.SYM)!!.scanCode)
        assertEquals("the second press opened the ALT step", CaptureStep.ALT, capture.current())

        capture.onKeyUp(56, KeyEvent.KEYCODE_ALT_LEFT, 4)
        assertEquals(56, capture.resultFor(CaptureStep.ALT)!!.scanCode)
        assertEquals(CaptureStep.SHIFT, capture.current())
    }

    @Test
    fun skipRecordsTheStepAsSkippedAndMovesOn() {
        val capture = GuidedCapture()
        capture.skip()
        assertEquals(CaptureStep.ALT, capture.current())
        assertTrue(capture.resultFor(CaptureStep.SYM)!!.skipped)
        assertFalse(capture.resultFor(CaptureStep.SYM)!!.isUsable)
        assertEquals(0, capture.usableResults().size)
    }

    @Test
    fun backReopensThePreviousStep() {
        val capture = GuidedCapture()
        capture.press(249, KeyEvent.KEYCODE_SYM)
        capture.back()

        assertEquals(CaptureStep.SYM, capture.current())
        assertNull(capture.resultFor(CaptureStep.SYM))

        capture.press(63, KeyEvent.KEYCODE_SYM)
        assertEquals(63, capture.resultFor(CaptureStep.SYM)!!.scanCode)
    }

    @Test
    fun navigationKeysAreNotCapturable() {
        val capture = GuidedCapture()
        assertFalse(capture.onKeyDown(158, KeyEvent.KEYCODE_BACK, 4, 0, 1_000L))
        assertFalse(capture.onKeyDown(0, KeyEvent.KEYCODE_VOLUME_UP, 4, 0, 1_000L))
        assertFalse(capture.onKeyUp(158, KeyEvent.KEYCODE_BACK, 4))

        assertEquals("Back must leave the step untouched", CaptureStep.SYM, capture.current())
        assertNull(capture.resultFor(CaptureStep.SYM))
    }

    @Test
    fun theSameKeyPressedTwiceIsWrittenOnce() {
        val capture = GuidedCapture()
        capture.press(249, KeyEvent.KEYCODE_SYM)          // SYM
        capture.press(249, KeyEvent.KEYCODE_SYM)          // ALT — same physical key
        capture.press(42, KeyEvent.KEYCODE_SHIFT_LEFT)    // SHIFT

        assertEquals(listOf(CaptureStep.ALT), capture.duplicateSteps())
        assertEquals(
            listOf(CaptureStep.SYM, CaptureStep.SHIFT),
            capture.usableResults().map { it.step },
        )
    }

    @Test
    fun runsToCompletionAndResetsBackToTheStart() {
        val capture = GuidedCapture()
        repeat(capture.steps().size) { capture.skip() }

        assertTrue(capture.isComplete)
        assertNull(capture.current())
        assertEquals(capture.steps().size, capture.answered())
        assertFalse(
            "a completed capture must ignore further keys",
            capture.onKeyDown(249, KeyEvent.KEYCODE_SYM, 4, 0, 1_000L),
        )

        capture.reset()
        assertEquals(CaptureStep.SYM, capture.current())
        assertEquals(0, capture.answered())
    }

    @Test
    fun anAutoRepeatOfAnAlreadyClosedKeyDoesNotOpenTheNextStep() {
        val capture = GuidedCapture()
        capture.press(14, KeyEvent.KEYCODE_DEL)
        // The kernel keeps repeating the key the user is still holding after its up was faked.
        assertTrue(capture.onKeyDown(14, KeyEvent.KEYCODE_DEL, 4, 4, 2_000L))
        assertNull(capture.resultFor(CaptureStep.ALT))
        assertEquals(CaptureStep.ALT, capture.current())
    }

    @Test
    fun theKeyUpOfAnAlreadyRecordedKeyIsSwallowed() {
        // A release the system sees without its press leaves a modifier's meta state stuck on.
        val capture = GuidedCapture()
        capture.onKeyDown(56, KeyEvent.KEYCODE_ALT_LEFT, 4, 0, 1_000L)
        capture.onKeyDown(42, KeyEvent.KEYCODE_SHIFT_LEFT, 4, 0, 2_000L)

        assertTrue(capture.onKeyUp(56, KeyEvent.KEYCODE_ALT_LEFT, 4))
        assertFalse(capture.onKeyUp(99, KeyEvent.KEYCODE_G, 4))
    }
}
