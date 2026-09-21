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
 * Characterisation tests for [MultiPointerGestureDetector] — the multi-pointer fork of AOSP's
 * `GestureDetector` that the IME feeds every touch through.
 *
 * These tests pin **what the detector does today**, not what it ought to do. Several assertions
 * below record behaviour that is plainly wrong (each marked `CHARACTERISED BUG:`); they are here so
 * a rewrite has to reproduce or deliberately change them rather than drift silently.
 *
 * Everything is driven through real [MotionEvent]s built with `PointerProperties` /
 * `PointerCoords` arrays rather than mocks, because pointer-*index* versus pointer-*id* confusion
 * is precisely the class of defect these tests exist to catch — `onTouchEvent` reads
 * `getPointerId(actionIndex)` in one place and indexes `getX(i)` by raw index in another, and a
 * mock cannot tell the two apart.
 *
 * Only observable outputs are asserted: the ordered sequence of listener callbacks, their float
 * arguments, and `onTouchEvent`'s return value. No private field is read.
 *
 * The handler timers ([android.view.ViewConfiguration.getTapTimeout] show-press,
 * `TAP_TIMEOUT + LONGPRESS_TIMEOUT` long-press, `DOUBLE_TAP_TIMEOUT` single-tap-confirm) are
 * scheduled with `sendMessageAtTime` against the *event's* timestamp, so every stream here is built
 * from `SystemClock.uptimeMillis()` and advanced with `ShadowLooper.idleFor`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MultiPointerGestureDetectorTest {

    // ── recording listeners ──────────────────────────────────────────────────

    /**
     * Records the callback sequence. Every callback extracts what it needs immediately: the
     * detector recycles some of the events it dispatches (the synthetic ACTION_CANCEL from
     * `cancelDoubleTapTracking`) as soon as the callback returns, so nothing may be retained.
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

    /**
     * The shape the IME actually uses: the constructor auto-registers any listener that also
     * implements `OnDoubleTapListener`, and `SimpleOnGestureListener` implements both, so in
     * production `doubleTapListener` is never null.
     */
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

    // ── fixture ──────────────────────────────────────────────────────────────

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

    // ── event construction ───────────────────────────────────────────────────

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

    // ── assertions (every test routes through one of these; see the report's
    //    "proved each test can fail" note — mutating these four turns the file red) ──

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

    // ═══════════════════════════════════════════════════════════════════════
    // 1. Single pointer — the degenerate case
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun singleTap_dispatchesDownThenSingleTapUpThenConfirm() {
        down(t0, 100f, 100f)
        up(t0 + 20, P(0, 100f, 100f))

        // onSingleTapConfirmed is dispatched SYNCHRONOUSLY from the UP, not from the
        // DOUBLE_TAP_TIMEOUT message (which the UP removes) — a deliberate divergence from AOSP.
        assertCalls("onDown", "onSingleTapUp", "onSingleTapConfirmed")
    }

    @Test
    fun theConfirmTimerIsRemovedByTheUpSoItNeverFiresAfterwards() {
        down(t0, 100f, 100f)
        up(t0 + 20, P(0, 100f, 100f))
        recorder.calls.clear()

        idle(doubleTapTimeout + longPressTimeout + tapTimeout + 50)

        // ACTION_UP calls removeMessages(3). That is also what makes double-tap detection
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

    // ═══════════════════════════════════════════════════════════════════════
    // 2. Timer thresholds — one case under, one over
    // ═══════════════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════════════
    // 3. Long press
    // ═══════════════════════════════════════════════════════════════════════

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

        // CHARACTERISED BUG: ACTION_UP re-posts the long-press message (and removes only
        // messages 1 and 3), while keeping the stored DOWN event — so a second onLongPress is
        // delivered after the finger has already left the screen.
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

        // CHARACTERISED BUG: onSingleTapConfirmed's only dispatch site is gated on
        // isLongpressEnabled, and the UP removes the confirm timer — so with long press off the
        // callback can never be delivered at all.
        assertCalls("onDown", "onSingleTapUp")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. Double tap
    // ═══════════════════════════════════════════════════════════════════════
    /**
     * CHARACTERISED BUG — the headline finding of this file.
     *
     * `ACTION_DOWN` decides "was this a double tap?" from `handler.hasMessages(3)`, where message 3
     * is the DOUBLE_TAP_TIMEOUT message it posts on the *previous* down. But `ACTION_UP` calls
     * `removeMessages(3)` (it dispatches `onSingleTapConfirmed` synchronously instead of waiting
     * for that timer). So by the time the second down arrives the gating message is always gone,
     * the `isConsideredDoubleTap` call is never made, and `onDoubleTap` is unreachable from any
     * tap-tap stream.
     *
     * Everything behind that gate is therefore dead today: `isConsideredDoubleTap`, the double-tap
     * slop, the 40 ms floor, the DOUBLE_TAP_TIMEOUT ceiling, `doubleTapRegion` / the CkbKeyGrid
     * keypad rectangle, and `activeDoubleTapPointerId` (which is only ever assigned inside that
     * same branch — see [noMoveOrUpEverTakesTheDoubleTapTrackingBranch]).
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
     * The same finding as a table: no combination of inter-tap gap and inter-tap distance — either
     * side of every threshold `isConsideredDoubleTap` would have applied — produces a double tap.
     *
     * These rows are what a rewrite has to reproduce. If Wave 3 repairs the gate, this test goes
     * red and the rows become the boundary table for the repaired behaviour: gaps of 39 ms and
     * `DOUBLE_TAP_TIMEOUT + 1` and a distance of exactly `doubleTapSlop` would stay rejections,
     * while 40 ms, `DOUBLE_TAP_TIMEOUT` and `doubleTapSlop - 1` would become double taps.
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
        // activeDoubleTapPointerId is only assigned inside the (unreachable) double-tap branch, so
        // it is permanently -1: every move falls into the cancel branch and no up ever forwards
        // an ACTION_UP to the double-tap listener.
        down(t0, 100f, 100f)
        up(t0 + 5, P(0, 100f, 100f))
        down(t0 + 55, 100f, 100f)
        recorder.calls.clear()

        move(t0 + 60, P(0, 100f + touchSlop, 100f))
        move(t0 + 65, P(0, 100f + touchSlop + 1, 100f))
        up(t0 + 70, P(0, 100f + touchSlop + 1, 100f))

        assertCalls(
            "onDoubleTapEvent:CANCEL",
            "onDoubleTapEvent:CANCEL",
            "onSingleTapUp",
            "onSingleTapConfirmed",
            // 17 px in 15 ms is well over the minimum fling velocity.
            "onFling",
        )
    }

    @Test
    fun everyPlainMoveDispatchesASyntheticCancelToTheDoubleTapListener() {
        // CHARACTERISED BUG: AOSP only forwards moves while a double tap is actually being
        // tracked. Here the `else` branch fires cancelDoubleTapTracking() on EVERY ACTION_MOVE of
        // every ordinary drag, so the listener sees a stream of ACTION_CANCEL events — one
        // obtainNoHistory()/recycle() pair per move, for a gesture that is not a double tap.
        down(t0, 100f, 100f)
        recorder.calls.clear()

        move(t0 + 5, P(0, 101f, 100f))
        move(t0 + 10, P(0, 102f, 100f))

        assertCalls("onDoubleTapEvent:CANCEL", "onDoubleTapEvent:CANCEL")
    }

    @Test
    fun withNoDoubleTapListenerAMoveDispatchesNothing() {
        val plain = GestureRecorder()
        detector = newDetector(plain)

        down(t0, 100f, 100f)
        move(t0 + 5, P(0, 101f, 100f))

        assertCallsOf(plain, "onDown")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. Fling
    // ═══════════════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════════════
    // 6. Multi-pointer streams
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * CHARACTERISED BUG — the pointer-index / pointer-id defect this file exists for.
     *
     * `onTouchEvent` resolves the pointer id it keys all its per-pointer state by from a switch
     * whose `default` arm (ACTION_DOWN, ACTION_MOVE, ACTION_CANCEL) hardcodes `pointerId = 0`;
     * only ACTION_UP / ACTION_POINTER_DOWN / ACTION_POINTER_UP read `getPointerId(actionIndex)`.
     *
     * So a single-finger stream whose pointer id is not 0 is *stored* under key 0 by its down, and
     * then *looked up* under its real id by its up — which finds no still-down flag and drops the
     * whole gesture.
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
        // The ACTION_MOVE loop looks up downEventByPointer by the event's real pointer id, which
        // for a non-zero-id stream is never the key the down wrote — so updateTapRegions is never
        // called and the tap timers survive a drag of any size.
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

        // CHARACTERISED BUG: ACTION_POINTER_DOWN only updates the (write-only) focal-point fields
        // and returns false. It never registers a DOWN event, tap region, or still-down flag for
        // the new pointer, so nothing about the second finger can ever be reported.
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

        // CHARACTERISED BUG: id 1 was never registered (ACTION_POINTER_DOWN registers nothing, and
        // the ACTION_DOWN that did register keyed everything under 0), so the terminating
        // ACTION_UP — which reads getPointerId(0) and gets 1 — finds no still-down flag and drops
        // the whole gesture: no onSingleTapUp, no onSingleTapConfirmed, no fling check. The
        // POINTER_UP of finger 0 only produced a double-tap event because confirmSingleTapOnUp was
        // still set for it.
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

    // ═══════════════════════════════════════════════════════════════════════
    // 7. Cancellation and non-terminating streams
    // ═══════════════════════════════════════════════════════════════════════

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
