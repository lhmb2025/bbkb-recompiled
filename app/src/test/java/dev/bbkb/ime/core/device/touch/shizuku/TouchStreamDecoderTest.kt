package dev.bbkb.ime.core.device.touch.shizuku

import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.ABS_MT_POSITION_X
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.ABS_MT_POSITION_Y
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.ABS_MT_SLOT
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.ABS_MT_TOUCH_MAJOR
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.ABS_MT_TOUCH_MINOR
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.ABS_MT_TRACKING_ID
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.ABS_X
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.ABS_Y
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.BTN_TOUCH
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.EV_ABS
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.EV_KEY
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.EV_SYN
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.SYN_DROPPED
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.SYN_MT_REPORT
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.SYN_REPORT
import dev.bbkb.ime.core.device.touch.shizuku.TouchAction.CANCEL
import dev.bbkb.ime.core.device.touch.shizuku.TouchAction.DOWN
import dev.bbkb.ime.core.device.touch.shizuku.TouchAction.MOVE
import dev.bbkb.ime.core.device.touch.shizuku.TouchAction.UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single-contact decoding rules, one per test, against hand-built evdev streams shaped the
 * way real drivers send them (Titan 2 `touchPad`: ABS_MT 0..1440 x 0..720, one contact).
 */
class TouchStreamDecoderTest {

    private val mtPad = TouchDeviceInfo(
        path = "/dev/input/event6", name = "touchPad",
        absX = AxisRange(0, 1440), absY = AxisRange(0, 720),
        mtX = AxisRange(0, 1440), mtY = AxisRange(0, 720),
        hasBtnTouch = true, hasTrackingId = true, mtSlots = 10,
    )

    private val singleTouchPad = TouchDeviceInfo(
        path = "/dev/input/event3", name = "touch_keypad",
        absX = AxisRange(0, 1079), absY = AxisRange(0, 599),
        hasBtnTouch = true,
    )

    /** Builds a packed stream; [t] is the current timestamp in microseconds. */
    private class Stream(var t: Long = 1_000_000L) {
        private val words = ArrayList<Long>()
        fun abs(code: Int, value: Int) = add(EV_ABS, code, value)
        fun key(code: Int, value: Int) = add(EV_KEY, code, value)
        fun syn(stepUs: Long = 10_000L) {
            add(EV_SYN, SYN_REPORT, 0)
            t += stepUs
        }
        fun dropped() = add(EV_SYN, SYN_DROPPED, 0)
        fun add(type: Int, code: Int, value: Int) {
            words.add(t)
            words.add(EvdevPacking.word(type, code, value))
        }
        fun take(): LongArray = words.toLongArray().also { words.clear() }
    }

    private fun decode(decoder: TouchStreamDecoder, vararg batches: LongArray): List<DecodedTouch> {
        val out = ArrayList<DecodedTouch>()
        batches.forEach { decoder.feed(it) { touch -> out.add(touch) } }
        return out
    }

    private fun List<DecodedTouch>.shape() = map { Triple(it.action, it.x, it.y) }

    @Test
    fun aMultiTouchContactGoesDownMovesAndLifts() {
        val s = Stream()
        s.abs(ABS_MT_TRACKING_ID, 7); s.abs(ABS_MT_POSITION_X, 100); s.abs(ABS_MT_POSITION_Y, 200)
        s.key(BTN_TOUCH, 1); s.syn()
        s.abs(ABS_MT_POSITION_X, 110); s.syn()
        s.abs(ABS_MT_POSITION_Y, 210); s.syn()
        s.abs(ABS_MT_TRACKING_ID, -1); s.key(BTN_TOUCH, 0); s.syn()

        val touches = decode(TouchStreamDecoder(mtPad), s.take())

        assertEquals(
            listOf(Triple(DOWN, 100, 200), Triple(MOVE, 110, 200), Triple(MOVE, 110, 210), Triple(UP, 110, 210)),
            touches.shape(),
        )
        assertEquals(listOf(1000L, 1010L, 1020L, 1030L), touches.map { it.eventTimeMs })
        assertTrue(touches.all { it.downTimeMs == 1000L })
        assertTrue(touches.all { it.device === mtPad })
    }

    @Test
    fun trackingIdMinusOneAloneIsALift() {
        val s = Stream()
        s.abs(ABS_MT_TRACKING_ID, 3); s.abs(ABS_MT_POSITION_X, 50); s.abs(ABS_MT_POSITION_Y, 60); s.syn()
        s.abs(ABS_MT_TRACKING_ID, -1); s.syn()

        val decoder = TouchStreamDecoder(mtPad.copy(hasBtnTouch = false))
        assertEquals(listOf(Triple(DOWN, 50, 60), Triple(UP, 50, 60)), decode(decoder, s.take()).shape())
        assertFalse(decoder.isTouching)
    }

    @Test
    fun btnTouchAloneDrivesASingleTouchDevice() {
        val s = Stream()
        s.abs(ABS_X, 500); s.abs(ABS_Y, 300); s.key(BTN_TOUCH, 1); s.syn()
        s.abs(ABS_X, 520); s.syn()
        s.key(BTN_TOUCH, 0); s.syn()

        assertEquals(
            listOf(Triple(DOWN, 500, 300), Triple(MOVE, 520, 300), Triple(UP, 520, 300)),
            decode(TouchStreamDecoder(singleTouchPad), s.take()).shape(),
        )
    }

    @Test
    fun withoutMultiTouchAxesPositionFallsBackToAbsXAndIgnoresStrayMtPositions() {
        val s = Stream()
        s.abs(ABS_X, 10); s.abs(ABS_Y, 20); s.abs(ABS_MT_POSITION_X, 999); s.key(BTN_TOUCH, 1); s.syn()
        s.abs(ABS_MT_POSITION_Y, 999); s.syn()   // MT-only frame: no position change for this device
        s.key(BTN_TOUCH, 0); s.syn()

        assertEquals(
            listOf(Triple(DOWN, 10, 20), Triple(UP, 10, 20)),
            decode(TouchStreamDecoder(singleTouchPad), s.take()).shape(),
        )
    }

    @Test
    fun withMultiTouchAxesTheLegacyAbsXCopyIsIgnored() {
        val s = Stream()
        s.abs(ABS_MT_TRACKING_ID, 1); s.abs(ABS_MT_POSITION_X, 300); s.abs(ABS_MT_POSITION_Y, 400)
        s.abs(ABS_X, 301); s.abs(ABS_Y, 401); s.key(BTN_TOUCH, 1); s.syn()
        s.abs(ABS_X, 900); s.syn()

        assertEquals(listOf(Triple(DOWN, 300, 400)), decode(TouchStreamDecoder(mtPad), s.take()).shape())
    }

    @Test
    fun contactSizeJumpsAreIgnored() {
        // A second finger on the Titan pad shows up as TOUCH_MAJOR/MINOR growing, not as a new
        // contact; size alone must never produce a touch, and a jump with motion is one MOVE.
        val s = Stream()
        s.abs(ABS_MT_TRACKING_ID, 9); s.abs(ABS_MT_POSITION_X, 700); s.abs(ABS_MT_POSITION_Y, 300)
        s.abs(ABS_MT_TOUCH_MAJOR, 12); s.key(BTN_TOUCH, 1); s.syn()
        s.abs(ABS_MT_TOUCH_MAJOR, 60); s.abs(ABS_MT_TOUCH_MINOR, 45); s.syn()
        s.abs(ABS_MT_TOUCH_MAJOR, 61); s.syn()
        s.abs(ABS_MT_TOUCH_MAJOR, 14); s.abs(ABS_MT_POSITION_X, 760); s.syn()
        s.abs(ABS_MT_TRACKING_ID, -1); s.key(BTN_TOUCH, 0); s.syn()

        assertEquals(
            listOf(Triple(DOWN, 700, 300), Triple(MOVE, 760, 300), Triple(UP, 760, 300)),
            decode(TouchStreamDecoder(mtPad), s.take()).shape(),
        )
    }

    @Test
    fun aFrameWithNoPositionChangeEmitsNothing() {
        val s = Stream()
        s.abs(ABS_MT_TRACKING_ID, 2); s.abs(ABS_MT_POSITION_X, 5); s.abs(ABS_MT_POSITION_Y, 6); s.syn()
        s.syn()
        s.abs(ABS_MT_POSITION_X, 5); s.syn()   // a driver that re-reports the same value
        s.add(EV_SYN, SYN_MT_REPORT, 0); s.syn()

        assertEquals(listOf(Triple(DOWN, 5, 6)), decode(TouchStreamDecoder(mtPad), s.take()).shape())
    }

    @Test
    fun synDroppedCancelsTheContactAndDiscardsUntilTheNextReport() {
        val s = Stream()
        s.abs(ABS_MT_TRACKING_ID, 4); s.abs(ABS_MT_POSITION_X, 100); s.abs(ABS_MT_POSITION_Y, 100); s.syn()
        s.abs(ABS_MT_POSITION_X, 150); s.syn()
        val dropAt = s.t
        s.dropped()
        // Everything up to and including the next SYN_REPORT is stale and must be discarded.
        s.abs(ABS_MT_POSITION_X, 9999); s.abs(ABS_MT_TRACKING_ID, -1); s.key(BTN_TOUCH, 0); s.syn()
        // The finger that was down carries on after the resync: still ignored...
        s.abs(ABS_MT_POSITION_X, 180); s.syn()
        // ...and its lift is not a gesture either.
        s.key(BTN_TOUCH, 0); s.syn()
        // A genuinely new touch starts normally.
        s.abs(ABS_MT_TRACKING_ID, 5); s.abs(ABS_MT_POSITION_X, 400); s.key(BTN_TOUCH, 1); s.syn()

        val decoder = TouchStreamDecoder(mtPad)
        val touches = decode(decoder, s.take())

        assertEquals(
            listOf(Triple(DOWN, 100, 100), Triple(MOVE, 150, 100), Triple(CANCEL, 150, 100), Triple(DOWN, 400, 100)),
            touches.shape(),
        )
        assertEquals(dropAt / 1000, touches[2].eventTimeMs)
        assertTrue(decoder.isTouching)
    }

    @Test
    fun synDroppedWhileIdleEmitsNothing() {
        val s = Stream()
        s.dropped(); s.abs(ABS_MT_TRACKING_ID, 1); s.syn()
        assertTrue(decode(TouchStreamDecoder(mtPad), s.take()).isEmpty())
    }

    @Test
    fun severalFramesInOneBatchDecodeInOrder() {
        val s = Stream()
        s.abs(ABS_MT_TRACKING_ID, 1); s.abs(ABS_MT_POSITION_X, 1); s.abs(ABS_MT_POSITION_Y, 1); s.syn()
        s.abs(ABS_MT_POSITION_X, 2); s.syn()
        s.abs(ABS_MT_POSITION_X, 3); s.syn()
        s.abs(ABS_MT_TRACKING_ID, -1); s.syn()

        val touches = decode(TouchStreamDecoder(mtPad), s.take())
        assertEquals(listOf(DOWN, MOVE, MOVE, UP), touches.map { it.action })
        assertEquals(listOf(1, 2, 3, 3), touches.map { it.x })
    }

    @Test
    fun aFrameSplitAcrossBatchesDecodesTheSameAsWhole() {
        val s = Stream()
        s.abs(ABS_MT_TRACKING_ID, 1); s.abs(ABS_MT_POSITION_X, 40)
        val first = s.take()
        s.abs(ABS_MT_POSITION_Y, 50); s.key(BTN_TOUCH, 1); s.syn()
        val second = s.take()

        assertEquals(listOf(Triple(DOWN, 40, 50)), decode(TouchStreamDecoder(mtPad), first, second).shape())
    }

    @Test
    fun aTapInsideOneFrameIsDownThenUp() {
        val s = Stream()
        s.abs(ABS_MT_TRACKING_ID, 8); s.abs(ABS_MT_POSITION_X, 70); s.abs(ABS_MT_POSITION_Y, 80)
        s.abs(ABS_MT_TRACKING_ID, -1); s.syn()

        val decoder = TouchStreamDecoder(mtPad)
        assertEquals(listOf(Triple(DOWN, 70, 80), Triple(UP, 70, 80)), decode(decoder, s.take()).shape())
        assertFalse(decoder.isTouching)
    }

    @Test
    fun aSecondFingerInAnotherSlotIsNeverEmitted() {
        val s = Stream()
        s.abs(ABS_MT_SLOT, 0); s.abs(ABS_MT_TRACKING_ID, 1)
        s.abs(ABS_MT_POSITION_X, 100); s.abs(ABS_MT_POSITION_Y, 100); s.key(BTN_TOUCH, 1); s.syn()
        s.abs(ABS_MT_SLOT, 1); s.abs(ABS_MT_TRACKING_ID, 2)
        s.abs(ABS_MT_POSITION_X, 900); s.abs(ABS_MT_POSITION_Y, 600); s.syn()
        s.abs(ABS_MT_POSITION_X, 950); s.syn()
        s.abs(ABS_MT_SLOT, 0); s.abs(ABS_MT_POSITION_X, 120); s.syn()
        s.abs(ABS_MT_SLOT, 1); s.abs(ABS_MT_TRACKING_ID, -1); s.syn()
        s.abs(ABS_MT_SLOT, 0); s.abs(ABS_MT_TRACKING_ID, -1)
        s.abs(ABS_MT_SLOT, 1); s.key(BTN_TOUCH, 0); s.syn()   // slot selection moves on in the lift frame

        assertEquals(
            listOf(Triple(DOWN, 100, 100), Triple(MOVE, 120, 100), Triple(UP, 120, 100)),
            decode(TouchStreamDecoder(mtPad), s.take()).shape(),
        )
    }

    @Test
    fun aNewTrackingIdInTheSameSlotIsALiftAndANewTouch() {
        val s = Stream()
        s.abs(ABS_MT_TRACKING_ID, 10); s.abs(ABS_MT_POSITION_X, 10); s.abs(ABS_MT_POSITION_Y, 10); s.syn()
        s.abs(ABS_MT_TRACKING_ID, 11); s.abs(ABS_MT_POSITION_X, 500); s.syn()

        val touches = decode(TouchStreamDecoder(mtPad), s.take())
        assertEquals(listOf(Triple(DOWN, 10, 10), Triple(UP, 10, 10), Triple(DOWN, 500, 10)), touches.shape())
        assertEquals(touches[2].eventTimeMs, touches[2].downTimeMs)
    }

    @Test
    fun anAxisThatDidNotChangeKeepsItsLastValueForTheNextTouch() {
        // Evdev only reports changes, so a second touch on the same column sends no X at all.
        val s = Stream()
        s.abs(ABS_MT_TRACKING_ID, 1); s.abs(ABS_MT_POSITION_X, 640); s.abs(ABS_MT_POSITION_Y, 100); s.syn()
        s.abs(ABS_MT_TRACKING_ID, -1); s.syn()
        s.abs(ABS_MT_TRACKING_ID, 2); s.abs(ABS_MT_POSITION_Y, 500); s.syn()

        assertEquals(
            listOf(Triple(DOWN, 640, 100), Triple(UP, 640, 100), Triple(DOWN, 640, 500)),
            decode(TouchStreamDecoder(mtPad), s.take()).shape(),
        )
    }

    @Test
    fun timestampsBecomeUptimeMillisByTruncatingMicroseconds() {
        val s = Stream(t = 123_456_789L)
        s.abs(ABS_MT_TRACKING_ID, 1); s.syn()
        assertEquals(123_456L, decode(TouchStreamDecoder(mtPad), s.take()).single().eventTimeMs)
    }

    @Test
    fun cancelEndsAContactInProgressAndIsANoOpOtherwise() {
        val decoder = TouchStreamDecoder(mtPad)
        assertNull(decoder.cancel(5L))

        val s = Stream()
        s.abs(ABS_MT_TRACKING_ID, 1); s.abs(ABS_MT_POSITION_X, 33); s.abs(ABS_MT_POSITION_Y, 44); s.syn()
        decode(decoder, s.take())
        val cancel = decoder.cancel(9_999L)

        assertEquals(DecodedTouch(CANCEL, 33, 44, 9_999L, 1000L, mtPad), cancel)
        assertFalse(decoder.isTouching)
        assertNull(decoder.cancel(10_000L))
    }

    @Test
    fun touchActionsCarryTheMotionEventConstants() {
        // MotionEvent.ACTION_DOWN/UP/MOVE/CANCEL; spelled out because the decoder is Android-free.
        assertEquals(listOf(0, 1, 2, 3), listOf(DOWN, UP, MOVE, CANCEL).map { it.motionEventAction })
    }
}
