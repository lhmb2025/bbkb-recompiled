package dev.bbkb.ime.core.gesture

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Characterisation tests for [MultiPointerGestureDetector]: they pin what it does today, including
 * the `CHARACTERISED BUG:` cases, so a rewrite must change them deliberately. Streams use real
 * [MotionEvent]s because a mock cannot tell pointer index from pointer id. Timers are scheduled
 * against the event's timestamp, so streams start at `SystemClock.uptimeMillis()` and advance with
 * `ShadowLooper.idleFor`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MultiPointerGestureDetectorTest {
    /**
     * Records the callback sequence. Callbacks copy what they need at once: the detector recycles
     * some dispatched events (the synthetic ACTION_CANCEL) as soon as the callback returns.
     */
    private open class GestureRecorder : MultiPointerGestureDetector.OnGestureListener {
        val calls = mutableListOf<String>()
        val flings = mutableListOf<FloatArray>()
        val scrolls = mutableListOf<FloatArray>()

        /** Every callback returns this, so return-value propagation can be pinned. */
        var handleEverything = false

        override fun onDown(e: MotionEvent): Boolean {
            calls += "onDown"; return handleEverything
        }

        override fun onShowPress(e: MotionEvent) {
            calls += "onShowPress"
        }

        override fun onLongPress(e: MotionEvent) {
            calls += "onLongPress"
        }

        override fun onScroll(e1: MotionEvent, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            calls += "onScroll"; scrolls += floatArrayOf(dx, dy); return handleEverything
        }

        override fun onFling(e1: MotionEvent, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            calls += "onFling"; flings += floatArrayOf(vx, vy); return handleEverything
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            calls += "onSingleTapUp"; return handleEverything
        }
    }

    /** The production shape: `SimpleOnGestureListener` is also an `OnDoubleTapListener`, so it is auto-registered. */
    private class FullRecorder : GestureRecorder(), MultiPointerGestureDetector.OnDoubleTapListener {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            calls += "onDoubleTap"; return handleEverything
        }

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            calls += "onDoubleTapEvent:" + actionName(e.actionMasked); return handleEverything
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            calls += "onSingleTapConfirmed"; return handleEverything
        }
    }

    private lateinit var context: Context
    private lateinit var recorder: FullRecorder
    private lateinit var detector: MultiPointerGestureDetector
    private var t0 = 0L

    /** Thresholds read from the live [ViewConfiguration] rather than hardcoded. */
    private var touchSlop = 0
    private var doubleTapSlop = 0
    private var minFlingVelocity = 0
    private var tapTimeout = 0L
    private var longPressTimeout = 0L
    private var doubleTapTimeout = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val vc = ViewConfiguration.get(context)
        touchSlop = vc.scaledTouchSlop
        doubleTapSlop = vc.scaledDoubleTapSlop
        minFlingVelocity = vc.scaledMinimumFlingVelocity
        tapTimeout = ViewConfiguration.getTapTimeout().toLong()
        longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
        recorder = FullRecorder()
        detector = newDetector(recorder)
        t0 = SystemClock.uptimeMillis()
    }

    private fun newDetector(listener: MultiPointerGestureDetector.OnGestureListener) =
        MultiPointerGestureDetector(context, listener, null, null)

    private data class P(val id: Int, val x: Float, val y: Float)

    private fun event(
        action: Int,
        at: Long,
        pointers: List<P>,
        actionIndex: Int = 0,
        deviceId: Int = 0,
    ): MotionEvent {
        val props = Array(pointers.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = pointers[i].id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(pointers.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = pointers[i].x
                y = pointers[i].y
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            t0, at,
            action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            pointers.size, props, coords,
            0, 0, 1f, 1f, deviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    private fun down(at: Long, x: Float, y: Float, id: Int = 0, deviceId: Int = 0) =
        detector.onTouchEvent(event(MotionEvent.ACTION_DOWN, at, listOf(P(id, x, y)), deviceId = deviceId))

    private fun move(at: Long, vararg pointers: P, deviceId: Int = 0) =
        detector.onTouchEvent(event(MotionEvent.ACTION_MOVE, at, pointers.toList(), deviceId = deviceId))

    private fun up(at: Long, vararg pointers: P, deviceId: Int = 0) =
        detector.onTouchEvent(event(MotionEvent.ACTION_UP, at, pointers.toList(), deviceId = deviceId))

    private fun pointerDown(at: Long, index: Int, vararg pointers: P) =
        detector.onTouchEvent(
            event(MotionEvent.ACTION_POINTER_DOWN, at, pointers.toList(), actionIndex = index)
        )

    private fun pointerUp(at: Long, index: Int, vararg pointers: P) =
        detector.onTouchEvent(
            event(MotionEvent.ACTION_POINTER_UP, at, pointers.toList(), actionIndex = index)
        )

    private fun cancel(at: Long, vararg pointers: P) =
        detector.onTouchEvent(event(MotionEvent.ACTION_CANCEL, at, pointers.toList()))

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun assertCalls(vararg expected: String) = assertCallsOf(recorder, *expected)

    private fun assertCallsOf(r: GestureRecorder, vararg expected: String) =
        assertEquals(expected.toList(), r.calls)

    private fun assertCount(message: String, expected: Int, actual: Int) =
        assertEquals(message, expected, actual)

    private fun assertFired(name: String) =
        assertTrue("expected $name in ${recorder.calls}", recorder.calls.contains(name))

    private fun assertNotFired(name: String) =
        assertFalse("did not expect $name in ${recorder.calls}", recorder.calls.contains(name))

    private fun assertFloat(message: String, expected: Float, actual: Float) =
        assertEquals(message, expected, actual, 0.01f)

    private fun assertReturned(expected: Boolean, actual: Boolean) =
        assertEquals(expected, actual)

    @Test
    fun singleTap_dispatchesDownThenSingleTapUpThenConfirm() {
        down(t0, 100f, 100f)
        up(t0 + 20, P(0, 100f, 100f))

        // onSingleTapConfirmed is dispatched synchronously from the UP, not from the
        // DOUBLE_TAP_TIMEOUT message (which the UP removes), unlike AOSP.
        assertCalls("onDown", "onSingleTapUp", "onSingleTapConfirmed")
    }

    @Test
    fun theConfirmTimerIsRemovedByTheUpSoItNeverFiresAfterwards() {
        down(t0, 100f, 100f)
        up(t0 + 20, P(0, 100f, 100f))
        recorder.calls.clear()

        idle(doubleTapTimeout + longPressTimeout + tapTimeout + 50)
        // ACTION_UP calls removeMessages(3), which also makes double taps unreachable (see
        // noTapPairIsEverReportedAsADoubleTap).
        // unreachable — see noTapPairIsEverReportedAsADoubleTap below.
        assertNotFired("onSingleTapConfirmed")
    }

    @Test
    fun returnValueIsTheDisjunctionOfTheListenerAnswers() {
        recorder.handleEverything = true

        assertReturned(true, down(t0, 100f, 100f))
        assertReturned(true, up(t0 + 20, P(0, 100f, 100f)))
    }

    @Test
    fun returnValueIsFalseWhenTheListenerHandlesNothing() {
        assertReturned(false, down(t0, 100f, 100f))
        assertReturned(false, up(t0 + 20, P(0, 100f, 100f)))
    }

    @Test
    fun anUpWithNoMatchingDownIsDropped() {
        assertReturned(false, up(t0, P(0, 100f, 100f)))
        assertCalls()
    }

    @Test
    fun anUnknownActionIsDropped() {
        down(t0, 100f, 100f)
        recorder.calls.clear()

        assertReturned(false, detector.onTouchEvent(event(MotionEvent.ACTION_OUTSIDE, t0 + 5, listOf(P(0, 100f, 100f)))))
        assertCalls()
    }

    @Test
    fun showPressFiresExactlyAtTapTimeout() {
        down(t0, 100f, 100f)

        idle(tapTimeout - 1)
        assertNotFired("onShowPress")

        idle(1)
        assertFired("onShowPress")
    }

    @Test
    fun longPressFiresExactlyAtTapTimeoutPlusLongPressTimeout() {
        down(t0, 100f, 100f)

        idle(tapTimeout + longPressTimeout - 1)
        assertNotFired("onLongPress")

        idle(1)
        assertFired("onLongPress")
    }

    @Test
    fun aMoveOfExactlyTouchSlopKeepsTheTapTimersAlive() {
        // updateTapRegions cancels the timers on `distanceSquared > touchSlopSquare`, so a
        // displacement of exactly touchSlop is still "in the tap region".
        down(t0, 100f, 100f)
        move(t0 + 2, P(0, 100f + touchSlop, 100f))

        idle(tapTimeout + longPressTimeout)

        assertFired("onShowPress")
        assertFired("onLongPress")
    }

    @Test
    fun aMoveOneMoreThanTouchSlopCancelsEveryTapTimer() {
        down(t0, 100f, 100f)
        move(t0 + 2, P(0, 100f + touchSlop + 1, 100f))

        idle(tapTimeout + longPressTimeout + doubleTapTimeout)

        assertNotFired("onShowPress")
        assertNotFired("onLongPress")
        assertNotFired("onSingleTapConfirmed")
    }

    @Test
    fun theConfirmTimerDoesNotFireWhileTheFingerIsStillDown() {
        down(t0, 100f, 100f)

        idle(doubleTapTimeout)

        // The DOUBLE_TAP_TIMEOUT message defers instead of confirming when the pointer is down.
        assertNotFired("onSingleTapConfirmed")
        assertFired("onShowPress")
    }

    @Test
    fun anUpAfterALongPressDispatchesScrollCarryingTheFocalPointNotADelta() {
        down(t0, 100f, 200f)
        idle(tapTimeout + longPressTimeout)
        recorder.calls.clear()

        up(t0 + 700, P(0, 100f, 200f))

        assertCalls("onSingleTapUp", "onScroll", "onSingleTapConfirmed")
        // CHARACTERISED BUG: onScroll's distanceX/distanceY contract is a delta; the detector
        // passes the absolute focal coordinates of the event instead.
        assertFloat("distanceX is the focal X", 100f, recorder.scrolls[0][0])
        assertFloat("distanceY is the focal Y", 200f, recorder.scrolls[0][1])
    }

    @Test
    fun theUpAfterALongPressRearmsTheLongPressTimerSoItFiresAgainWithNoFingerDown() {
        down(t0, 100f, 100f)
        idle(tapTimeout + longPressTimeout)
        val upAt = t0 + 700
        up(upAt, P(0, 100f, 100f))
        recorder.calls.clear()

        // The re-armed message is scheduled against the UP's own event time.
        idle(upAt + tapTimeout + longPressTimeout - SystemClock.uptimeMillis() + 1)

        // CHARACTERISED BUG: ACTION_UP re-posts the long-press message while keeping the stored
        // DOWN, so a second onLongPress arrives after the finger has left.
        assertFired("onLongPress")
    }

    @Test
    fun everyMoveDuringALongPressDispatchesFlingCarryingTheFocalPointNotAVelocity() {
        down(t0, 100f, 100f)
        idle(tapTimeout + longPressTimeout)
        recorder.calls.clear()

        move(t0 + 700, P(0, 140f, 160f))

        assertFired("onFling")
        // CHARACTERISED BUG: the velocityX/velocityY arguments are the focal coordinates.
        assertFloat("velocityX is the focal X", 140f, recorder.flings[0][0])
        assertFloat("velocityY is the focal Y", 160f, recorder.flings[0][1])
    }

    @Test
    fun longPressCanBeDisabled() {
        detector.setIsLongpressEnabled(false)
        down(t0, 100f, 100f)

        idle(tapTimeout + longPressTimeout + 50)

        assertNotFired("onLongPress")
        assertFired("onShowPress")
    }

    @Test
    fun disablingLongPressAlsoSuppressesSingleTapConfirmedEntirely() {
        detector.setIsLongpressEnabled(false)

        down(t0, 100f, 100f)
        up(t0 + 20, P(0, 100f, 100f))
        idle(doubleTapTimeout + longPressTimeout)

        // CHARACTERISED BUG: the only onSingleTapConfirmed dispatch is gated on isLongpressEnabled
        // and the UP removes the confirm timer, so with long press off it never fires.
        assertCalls("onDown", "onSingleTapUp")
    }

    /**
     * CHARACTERISED BUG: ACTION_DOWN detects a double tap via `handler.hasMessages(3)`, but
     * ACTION_UP already called `removeMessages(3)`, so `onDoubleTap` is unreachable. Everything
     * behind that gate (slop, 40 ms floor, timeout ceiling, keypad rectangle,
     * `activeDoubleTapPointerId`) is dead.
     */
    @Test
    fun twoTapsInTimeAndInPlaceAreStillNotADoubleTap() {
        down(t0, 100f, 100f)
        up(t0 + 5, P(0, 100f, 100f))
        recorder.calls.clear()

        down(t0 + 55, 100f, 100f)

        assertCalls("onDown")
    }

    /**
     * The same finding as a table across every threshold. If the gate is repaired, these rows become
     * the boundary table: 39 ms, `DOUBLE_TAP_TIMEOUT + 1` and exactly `doubleTapSlop` stay
     * rejections; 40 ms, `DOUBLE_TAP_TIMEOUT` and `doubleTapSlop - 1` become double taps.
     */
    @Test
    fun noTapPairIsEverReportedAsADoubleTap() {
        val gaps = listOf(39L, 40L, 50L, doubleTapTimeout, doubleTapTimeout + 1)
        val distances = listOf(0, doubleTapSlop - 1, doubleTapSlop)

        for (gap in gaps) {
            for (distance in distances) {
                recorder = FullRecorder()
                detector = newDetector(recorder)
                t0 = SystemClock.uptimeMillis()

                down(t0, 100f, 100f)
                up(t0 + 5, P(0, 100f, 100f))
                recorder.calls.clear()
                down(t0 + 5 + gap, 100f + distance, 100f)

                assertCalls("onDown")
            }
        }
    }

    @Test
    fun noMoveOrUpEverTakesTheDoubleTapTrackingBranch() {
        // activeDoubleTapPointerId is only assigned in the unreachable double-tap branch, so it
        // stays -1: no move cancels and no up forwards to the double-tap listener.
        down(t0, 100f, 100f)
        up(t0 + 5, P(0, 100f, 100f))
        down(t0 + 55, 100f, 100f)
        recorder.calls.clear()

        move(t0 + 60, P(0, 100f + touchSlop, 100f))
        move(t0 + 65, P(0, 100f + touchSlop + 1, 100f))
        up(t0 + 70, P(0, 100f + touchSlop + 1, 100f))

        assertCalls(
            "onSingleTapUp",
            "onSingleTapConfirmed",
            // 17 px in 15 ms is well over the minimum fling velocity.
            "onFling",
        )
    }

    @Test
    fun aPlainMoveDispatchesNoSyntheticCancelToTheDoubleTapListener() {
        // Moves are forwarded to the double-tap listener only while a double tap is tracked, so an
        // ordinary drag produces no ACTION_CANCEL events.
        down(t0, 100f, 100f)
        recorder.calls.clear()

        move(t0 + 5, P(0, 101f, 100f))
        move(t0 + 10, P(0, 102f, 100f))

        assertCalls()
    }

    @Test
    fun withNoDoubleTapListenerAMoveDispatchesNothing() {
        val plain = GestureRecorder()
        detector = newDetector(plain)

        down(t0, 100f, 100f)
        move(t0 + 5, P(0, 101f, 100f))

        assertCallsOf(plain, "onDown")
    }

    @Test
    fun aVelocityOfExactlyTheMinimumIsNotAFling() {
        // checkFling's fallback velocity is (dx * 1000) / dt; the gate is strictly `>`.
        val dt = 100L
        val dx = minFlingVelocity * dt / 1000f
        down(t0, 0f, 0f)
        up(t0 + dt, P(0, dx, 0f))

        assertNotFired("onFling")
    }

    @Test
    fun aVelocityOneStepOverTheMinimumIsAFling() {
        val dt = 100L
        val dx = (minFlingVelocity + 10) * dt / 1000f
        down(t0, 0f, 0f)
        up(t0 + dt, P(0, dx, 0f))

        assertFired("onFling")
        assertFloat("velocityX", (minFlingVelocity + 10).toFloat(), recorder.flings[0][0])
        assertFloat("velocityY", 0f, recorder.flings[0][1])
    }

    @Test
    fun theVerticalAxisIsCheckedIndependently() {
        val dt = 100L
        val dy = (minFlingVelocity + 10) * dt / 1000f
        down(t0, 0f, 0f)
        up(t0 + dt, P(0, 0f, dy))

        assertFired("onFling")
        assertFloat("velocityY", (minFlingVelocity + 10).toFloat(), recorder.flings[0][1])
    }

    @Test
    fun aZeroDurationGestureIsNeverAFling() {
        // The simple-velocity fallback is skipped when the up and down share a timestamp.
        down(t0, 0f, 0f)
        up(t0, P(0, 500f, 0f))

        assertNotFired("onFling")
    }

    @Test
    fun aPointerUpNeverChecksForAFling() {
        down(t0, 0f, 0f)
        pointerDown(t0 + 5, 1, P(0, 0f, 0f), P(1, 300f, 0f))
        recorder.calls.clear()

        pointerUp(t0 + 105, 0, P(0, 500f, 0f), P(1, 300f, 0f))

        assertNotFired("onFling")
    }

    /**
     * CHARACTERISED BUG: `onTouchEvent` keys per-pointer state by `pointerId = 0` for
     * ACTION_DOWN/MOVE/CANCEL but by `getPointerId(actionIndex)` for UP and POINTER_DOWN/UP, so a
     * single-finger stream with a non-zero id is stored under 0 and its up finds nothing.
     */
    @Test
    fun aDownStoresItsStateUnderPointerZeroWhateverTheRealPointerIdIs() {
        down(t0, 100f, 100f, id = 5)

        // The timers fire: they were keyed 0 by the down, and the show-press message carries that
        // same 0, so it finds the stored event.
        idle(tapTimeout + longPressTimeout)

        assertFired("onShowPress")
        assertFired("onLongPress")
    }

    @Test
    fun theUpOfANonZeroPointerIdIsDroppedBecauseItsDownWasStoredUnderPointerZero() {
        down(t0, 100f, 100f, id = 5)
        recorder.calls.clear()

        assertReturned(false, up(t0 + 20, P(5, 100f, 100f)))
        assertCalls()
    }

    @Test
    fun aMoveIsMatchedToItsDownByRealPointerIdSoANonZeroPointerNeverLeavesTheTapRegion() {
        // ACTION_MOVE looks up downEventByPointer by the real id, which never matches the 0 key the
        // down wrote, so the tap timers survive a drag of any size.
        down(t0, 100f, 100f, id = 5)
        move(t0 + 5, P(5, 900f, 900f))

        idle(tapTimeout + longPressTimeout)

        assertFired("onShowPress")
        assertFired("onLongPress")
    }

    @Test
    fun aSecondFingerGoingDownIsNotTrackedAtAll() {
        down(t0, 100f, 100f)
        recorder.calls.clear()

        // CHARACTERISED BUG: ACTION_POINTER_DOWN registers nothing for the new pointer and returns
        // false, so nothing about the second finger can be reported.
        assertReturned(false, pointerDown(t0 + 5, 1, P(0, 100f, 100f), P(1, 300f, 100f)))
        assertCalls()
    }

    @Test
    fun theSecondFingerCanBeDraggedAnywhereWithoutDisturbingTheFirstFingersTapTimers() {
        down(t0, 100f, 100f)
        pointerDown(t0 + 5, 1, P(0, 100f, 100f), P(1, 300f, 100f))

        // Pointer 1 travels far past the slop; pointer 0 does not move.
        move(t0 + 10, P(0, 100f, 100f), P(1, 900f, 700f))
        idle(tapTimeout + longPressTimeout)

        assertFired("onShowPress")
        assertFired("onLongPress")
    }

    @Test
    fun theFocalPointIsTheMeanOfEveryPointer() {
        down(t0, 100f, 100f)
        pointerDown(t0 + 5, 1, P(0, 100f, 100f), P(1, 300f, 100f))
        idle(tapTimeout + longPressTimeout)
        recorder.calls.clear()

        // In the long-press branch the focal point is what lands in onFling's velocity arguments.
        move(t0 + 700, P(0, 100f, 200f), P(1, 300f, 400f))

        assertFired("onFling")
        assertFloat("focal X", 200f, recorder.flings[0][0])
        assertFloat("focal Y", 300f, recorder.flings[0][1])
    }

    @Test
    fun secondFingerUpFirst_thenTheFirstFingerCompletesNormally() {
        down(t0, 100f, 100f)
        pointerDown(t0 + 5, 1, P(0, 100f, 100f), P(1, 300f, 100f))
        recorder.calls.clear()

        // Release order A: the second finger (index 1, id 1) leaves first.
        assertReturned(false, pointerUp(t0 + 20, 1, P(0, 100f, 100f), P(1, 300f, 100f)))
        up(t0 + 40, P(0, 100f, 100f))

        // The POINTER_UP is dropped ("no matching down event" for id 1) and the real UP behaves
        // exactly like an ordinary single tap.
        assertCalls("onSingleTapUp", "onSingleTapConfirmed")
    }

    @Test
    fun firstFingerUpFirst_theRemainingFingerIsSilentlyDroppedBecauseIdAndIndexDiverge() {
        down(t0, 100f, 100f)
        pointerDown(t0 + 5, 1, P(0, 100f, 100f), P(1, 300f, 100f))
        recorder.calls.clear()

        // Release order B: the FIRST finger (index 0, id 0) leaves, so pointer id 1 now sits at
        // index 0 and the final ACTION_UP reports pointerId == 1.
        pointerUp(t0 + 20, 0, P(0, 100f, 100f), P(1, 300f, 100f))
        val handledFinalUp = up(t0 + 40, P(1, 300f, 100f))

        // CHARACTERISED BUG: id 1 was never registered, so the final ACTION_UP (getPointerId(0) == 1)
        // drops the whole gesture. Finger 0's POINTER_UP produced a double-tap event only because
        // confirmSingleTapOnUp was still set.
        assertReturned(false, handledFinalUp)
        assertCalls("onDoubleTapEvent:POINTER_UP")
    }

    @Test
    fun aPointerUpForAnUnknownPointerIsDropped() {
        down(t0, 100f, 100f)
        recorder.calls.clear()

        assertReturned(false, pointerUp(t0 + 20, 1, P(0, 100f, 100f), P(1, 300f, 100f)))
        assertCalls()
    }

    @Test
    fun actionCancelMidGestureDropsEverythingAndSilencesTheTimers() {
        down(t0, 100f, 100f)
        move(t0 + 5, P(0, 102f, 100f))
        recorder.calls.clear()

        assertReturned(false, cancel(t0 + 10, P(0, 102f, 100f)))
        idle(tapTimeout + longPressTimeout + doubleTapTimeout)

        assertCalls()
    }

    @Test
    fun anUpAfterACancelIsDropped() {
        down(t0, 100f, 100f)
        cancel(t0 + 10, P(0, 100f, 100f))
        recorder.calls.clear()

        assertReturned(false, up(t0 + 20, P(0, 100f, 100f)))
        assertCalls()
    }

    @Test
    fun aCancelAfterALongPressAlsoClearsTheLongPressState() {
        down(t0, 100f, 100f)
        idle(tapTimeout + longPressTimeout)
        cancel(SystemClock.uptimeMillis(), P(0, 100f, 100f))
        down(SystemClock.uptimeMillis(), 100f, 100f)
        recorder.calls.clear()

        move(SystemClock.uptimeMillis(), P(0, 140f, 160f))

        // inLongPress was reset by cancelTaps(), so this move is not a long-press drag.
        assertNotFired("onFling")
    }

    @Test
    fun aStreamThatNeverTerminatesKeepsBeingProcessed() {
        down(t0, 100f, 100f)
        move(t0 + 5, P(0, 101f, 100f))
        idle(tapTimeout + longPressTimeout)

        assertFired("onShowPress")
        assertFired("onLongPress")
        assertNotFired("onSingleTapUp")
        recorder.calls.clear()

        // Still live: further moves take the long-press branch indefinitely.
        move(SystemClock.uptimeMillis(), P(0, 102f, 100f))
        move(SystemClock.uptimeMillis(), P(0, 103f, 100f))

        assertCount("fling count", 2, recorder.flings.size)
    }

    @Test
    fun aChangeOfInputDeviceMidStreamCancelsTheGesture() {
        down(t0, 100f, 100f, deviceId = 3)
        recorder.calls.clear()

        // A different device id resets the detector before the event is processed.
        move(t0 + 5, P(0, 100f, 100f), deviceId = 4)
        idle(tapTimeout + longPressTimeout)
        val handledUp = up(t0 + 20, P(0, 100f, 100f), deviceId = 4)

        assertReturned(false, handledUp)
        assertNotFired("onLongPress")
        assertNotFired("onSingleTapUp")
    }

    private companion object {
        fun actionName(actionMasked: Int) = when (actionMasked) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
            MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
            else -> "ACTION_$actionMasked"
        }
    }
}
