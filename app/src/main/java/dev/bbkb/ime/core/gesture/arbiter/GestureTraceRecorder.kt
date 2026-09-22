package dev.bbkb.ime.core.gesture.arbiter

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import kotlin.math.max
import kotlin.math.min

/**
 * Turns the live CKB MotionEvent stream into completed [GestureTrace]s and detects tap-and-hold,
 * for the new gesture engine. Single-finger: it tracks the first pointer of a contact and ignores
 * the rest.
 *
 * Coordinates are normalized **isotropically** by the larger of the device's X/Y motion ranges, so
 * distances and angles are correct for [GestureClassifier] (the same space the Gesture Lab uses).
 *
 * Step 1 wiring is **observer-only**: [BlackBerryIME][dev.bbkb.ime.core.BlackBerryIME]
 * feeds events here for every CKB contact (the arbiter is the sole CKB engine), and the listener logs the verdict without
 * changing behavior. The recorder never consumes events.
 */
class GestureTraceRecorder(
    private val configProvider: () -> GestureConfig,
    private val listener: Listener,
    handler: Handler? = null,
) {
    interface Listener {
        /** A completed contact (UP). [previousTap] is the prior completed trace, for double-tap. */
        fun onTraceComplete(trace: GestureTrace, previousTap: GestureTrace?)

        /** Fired once mid-contact when a stationary touch passes the hold threshold. */
        fun onHold(trace: GestureTrace)
    }

    private val handler: Handler = handler ?: Handler(Looper.getMainLooper())
    private val raw = ArrayList<TracePoint>()
    private var ref = 1f
    private var capturing = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var holdFired = false
    private var prevTrace: GestureTrace? = null

    /**
     * Audit GD-1: the config snapshot for the current contact. [configProvider] used to be called
     * on every ACTION_MOVE sample even though the config cannot change mid-stroke; the production
     * provider reads SharedPreferences, so a swipe cost hundreds of locked map reads and
     * allocations for one float. Snapshotted at DOWN and reused for the whole contact.
     */
    private var contactConfig: GestureConfig? = null

    private fun cfg(): GestureConfig = contactConfig ?: configProvider().also { contactConfig = it }

    // Running bounds for cheap "stayed within tap slop" checks without rebuilding a GestureTrace.
    private var minX = 0f
    private var maxX = 0f
    private var minY = 0f
    private var maxY = 0f

    private val holdRunnable = Runnable { fireHold() }

    fun onMotionEvent(event: MotionEvent) {
        when (event.actionMasked) {
            // A secondary finger landing (POINTER_DOWN) starts a fresh trace on that finger:
            // chained flicks overlap contacts, and ignoring the new finger recorded its whole
            // stroke as nothing while the old finger's stub completed as a bogus zero-move Tap.
            // The in-flight previous trace is deliberately abandoned -- the newest contact wins.
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> start(event)
            MotionEvent.ACTION_MOVE -> move(event)
            MotionEvent.ACTION_UP -> end(event)
            // Only the tracked finger's lift ends the trace; another finger lifting is not ours.
            MotionEvent.ACTION_POINTER_UP ->
                if (event.getPointerId(event.actionIndex) == activePointerId) end(event)
            MotionEvent.ACTION_CANCEL -> cancel()
        }
    }

    private fun start(event: MotionEvent) {
        cancelHoldTimer()
        capturing = true
        holdFired = false
        activePointerId = event.getPointerId(event.actionIndex)
        val dev = event.device
        val rx = dev?.getMotionRange(MotionEvent.AXIS_X)?.range ?: 0f
        val ry = dev?.getMotionRange(MotionEvent.AXIS_Y)?.range ?: 0f
        // Synthetic events (gesture-replay rig) and a null/range-less InputDevice leave the
        // ranges at 0 — without a real reference the trace stays in raw pad pixels, every
        // normalized threshold in GestureConfig is trivially cleared, and any straight
        // word-swipe misclassifies as a ballistic flick (the replay corpus lost every
        // straight leftward word — of/tea/its/our/poor — to Act(delete_word) this way).
        // Fall back to the keypad coordinate-space width, which is what a real capacitive
        // keypad's motion range reports anyway.
        ref = max(max(rx, ry), 1f)
        if (ref <= 1f) ref = CkbKeyGrid.WIDTH.toFloat()
        raw.clear()
        addPoint(event, event.actionIndex, first = true)
        val cfg = configProvider()
        contactConfig = cfg
        handler.postDelayed(holdRunnable, cfg.holdMinDurationMs)
    }

    private fun move(event: MotionEvent) {
        if (!capturing) return
        val idx = event.findPointerIndex(activePointerId)
        if (idx < 0) return
        addPoint(event, idx, first = false)
        if (!holdFired && excursion() > cfg().tapSlop) {
            cancelHoldTimer() // moved too far — no longer a hold candidate
        }
    }

    private fun end(event: MotionEvent) {
        if (!capturing) return
        val idx = event.findPointerIndex(activePointerId)
        if (idx >= 0) addPoint(event, idx, first = false)
        cancelHoldTimer()
        capturing = false
        val trace = GestureTrace(ArrayList(raw))
        val previous = prevTrace
        prevTrace = trace
        listener.onTraceComplete(trace, previous)
    }

    private fun cancel() {
        cancelHoldTimer()
        capturing = false
        raw.clear()
    }

    private fun fireHold() {
        if (!capturing || holdFired) return
        if (excursion() > cfg().tapSlop) return
        holdFired = true
        listener.onHold(GestureTrace(ArrayList(raw)))
    }

    private fun addPoint(event: MotionEvent, pointerIndex: Int, first: Boolean) {
        val x = event.getX(pointerIndex) / ref
        val y = event.getY(pointerIndex) / ref
        raw.add(TracePoint(x, y, event.eventTime))
        if (first) {
            minX = x; maxX = x; minY = y; maxY = y
        } else {
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
        }
    }

    private fun excursion(): Float = max(maxX - minX, maxY - minY)

    private fun cancelHoldTimer() = handler.removeCallbacks(holdRunnable)
}
