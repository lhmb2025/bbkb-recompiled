package dev.bbkb.ime.core.gesture.arbiter

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import kotlin.math.max
import kotlin.math.min

/**
 * Turns the CKB MotionEvent stream into completed [GestureTrace]s and detects hold. Tracks one
 * pointer per contact. Coordinates are normalized isotropically by the larger of the device's
 * X/Y motion ranges. Never consumes events.
 */
class GestureTraceRecorder(
    private val configProvider: () -> GestureConfig,
    private val listener: Listener,
    handler: Handler? = null,
    /** Normalization reference when the InputDevice reports no motion ranges (keypad's larger dimension). */
    private val fallbackReference: () -> Float = {
        dev.bbkb.ime.core.device.touch.TouchKeypadGeometry.current().normalizationReference()
    },
) {
    interface Listener {
        /** [previousTap] is the prior completed trace, for double-tap. */
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

    /** Moved past the tap slop; hold timer already cancelled. */
    private var holdCancelled = false
    private var prevTrace: GestureTrace? = null

    /** Snapshotted at DOWN: the provider reads SharedPreferences, too costly per MOVE sample. */
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
            // POINTER_DOWN starts a fresh trace (chained flicks overlap contacts); the in-flight
            // trace is abandoned so the newest contact wins and no bogus zero-move Tap completes.
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
        holdCancelled = false
        activePointerId = event.getPointerId(event.actionIndex)
        val dev = event.device
        val rx = dev?.getMotionRange(MotionEvent.AXIS_X)?.range ?: 0f
        val ry = dev?.getMotionRange(MotionEvent.AXIS_Y)?.range ?: 0f
        // Synthetic events and range-less devices report 0; without a reference the trace stays
        // in raw pixels and every straight word-swipe clears the thresholds as a flick. Fall
        // back to the keypad frame's larger dimension (what a real pad's range reports).
        ref = max(max(rx, ry), 1f)
        if (ref <= 1f) ref = fallbackReference()
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
        if (!holdFired && !holdCancelled && excursion() > cfg().tapSlop) {
            cancelHoldTimer()
            holdCancelled = true // excursion only grows
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
