package dev.bbkb.ime.core.device.touch.shizuku

import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.ABS_MT_POSITION_X
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.ABS_MT_POSITION_Y
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.ABS_MT_SLOT
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.ABS_MT_TRACKING_ID
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.ABS_X
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.ABS_Y
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.BTN_TOUCH
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.EV_ABS
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.EV_KEY
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.EV_SYN
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.SYN_DROPPED
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.SYN_REPORT

/** What a [DecodedTouch] is. [motionEventAction] is the matching `MotionEvent.ACTION_*` value. */
enum class TouchAction(val motionEventAction: Int) {
    DOWN(0),
    UP(1),
    MOVE(2),
    CANCEL(3),
}

/**
 * One touch of a single-contact gesture, in the device's raw coordinates: scale with
 * [TouchDeviceInfo.xRange]/[TouchDeviceInfo.yRange] of [device].
 *
 * [eventTimeMs] and [downTimeMs] are on the SystemClock.uptimeMillis() timebase, so they can go
 * straight into MotionEvent.obtain(); see [TouchStreamDecoder] for the conversion.
 */
data class DecodedTouch(
    val action: TouchAction,
    val x: Int,
    val y: Int,
    val eventTimeMs: Long,
    val downTimeMs: Long,
    val device: TouchDeviceInfo,
)

/**
 * Turns packed evdev records ([EvdevPacking]) into DOWN/MOVE/UP/CANCEL for ONE contact. Pure
 * Kotlin: no Android types, so every rule below has a JVM test.
 *
 * Contact state:
 *  - BTN_TOUCH 1/0 and ABS_MT_TRACKING_ID >= 0/-1 are both read as down/up, whichever the
 *    device sends (most send both). A tracking id that changes without a -1 in between is a
 *    lift and a new touch in the same frame.
 *  - Position is ABS_MT_POSITION_X/Y when the device has both, else ABS_X/ABS_Y. Evdev only
 *    reports an axis when it changes, so the last value is remembered per slot.
 *  - Only the first contact is followed: with ABS_MT_SLOT, other slots are tracked but never
 *    emitted, and a finger that lands after the followed one has lifted is not picked up mid-
 *    stroke. ABS_MT_TOUCH_MAJOR/MINOR (and pressure) are ignored: on the Titan pad a second
 *    finger arrives as a jump in contact size, not as a new contact, and there is no reliable
 *    way to undo that, so it is simply not looked at.
 *
 * Frames: nothing is emitted until SYN_REPORT, which applies the whole frame at once (a tap that
 * starts and ends inside one frame gives DOWN then UP). MOVE is emitted only when the position
 * actually changed. SYN_DROPPED (the kernel's buffer overflowed) cancels a contact in progress
 * with CANCEL, then every record up to and including the next SYN_REPORT is discarded, per the
 * evdev documentation. The finger that was down stays ignored until it lifts: its lift may have
 * been among the lost records, so the next explicit touch-down is what starts a new gesture.
 *
 * Timebase: [EvdevUserService] asks for CLOCK_MONOTONIC event stamps (EVIOCSCLOCKID); where a
 * kernel refuses, it converts the default CLOCK_REALTIME stamps by the realtime-to-monotonic
 * offset sampled at each read (exact to about a millisecond, and wrong for the one read that
 * straddles a wall-clock step). The packed timestamps are therefore CLOCK_MONOTONIC
 * microseconds. SystemClock.uptimeMillis() is CLOCK_MONOTONIC in milliseconds (Android's
 * `systemTime(SYSTEM_TIME_MONOTONIC)`; it stops in deep sleep, exactly as these stamps do), and
 * CLOCK_MONOTONIC is one system-wide clock, so the shell-uid reader and the IME process agree
 * on it: `eventTimeMs = monotonicMicros / 1000` needs no offset and is what InputReader would
 * have stamped on a MotionEvent for the same contact.
 */
class TouchStreamDecoder(val device: TouchDeviceInfo) {

    private val useMtAxes = device.usesMtAxes

    private val slotX = IntArray(MAX_SLOTS)
    private val slotY = IntArray(MAX_SLOTS)
    private var slot = 0
    private var trackedSlot = NO_SLOT
    private var trackingId = NO_TRACKING_ID

    private var touching = false
    private var downTimeMs = 0L
    private var lastX = 0
    private var lastY = 0

    /** Discarding records after SYN_DROPPED until the next SYN_REPORT. */
    private var dropping = false

    private var frameSawDown = false
    private var frameSawUp = false
    private var frameLastSignal = SIGNAL_NONE
    private var frameLiftedSlot = NO_SLOT

    /** Whether a contact is down (a DOWN was emitted with no UP or CANCEL since). */
    val isTouching: Boolean get() = touching

    /** Decodes a batch, handing each touch to [sink] in order. Batches may split frames. */
    fun feed(packed: LongArray, sink: (DecodedTouch) -> Unit) {
        var i = 0
        while (i + 1 < packed.size) {
            onRecord(packed[i], packed[i + 1], sink)
            i += EvdevPacking.WORDS_PER_EVENT
        }
    }

    /**
     * Ends a contact in progress because the stream itself ended (device gone, service died,
     * engine stopping): returns the CANCEL at [timeMs] to deliver, or null if nothing was down.
     */
    fun cancel(timeMs: Long): DecodedTouch? {
        clearFrame()
        trackedSlot = NO_SLOT
        trackingId = NO_TRACKING_ID
        if (!touching) return null
        touching = false
        return DecodedTouch(TouchAction.CANCEL, lastX, lastY, timeMs, downTimeMs, device)
    }

    private fun onRecord(timeUs: Long, word: Long, sink: (DecodedTouch) -> Unit) {
        val type = EvdevPacking.type(word)
        val code = EvdevPacking.code(word)
        val value = EvdevPacking.value(word)
        if (dropping) {
            if (type == EV_SYN && code == SYN_REPORT) {
                dropping = false
                clearFrame()
            }
            return
        }
        when (type) {
            EV_SYN -> when (code) {
                SYN_REPORT -> endFrame(timeUs / 1000L, sink)
                SYN_DROPPED -> startDropping(timeUs / 1000L, sink)
            }
            EV_KEY -> if (code == BTN_TOUCH) {
                if (value != 0) signalDown() else signalUp()
            }
            EV_ABS -> onAbs(code, value)
        }
    }

    private fun onAbs(code: Int, value: Int) {
        when (code) {
            ABS_MT_SLOT -> slot = value
            ABS_MT_TRACKING_ID -> onTrackingId(value)
            ABS_MT_POSITION_X -> if (useMtAxes && slot in 0 until MAX_SLOTS) slotX[slot] = value
            ABS_MT_POSITION_Y -> if (useMtAxes && slot in 0 until MAX_SLOTS) slotY[slot] = value
            ABS_X -> if (!useMtAxes) slotX[0] = value
            ABS_Y -> if (!useMtAxes) slotY[0] = value
            // ABS_MT_TOUCH_MAJOR/MINOR, pressure, orientation...: deliberately ignored.
        }
    }

    private fun onTrackingId(value: Int) {
        if (value < 0) {
            if (trackedSlot != NO_SLOT && slot == trackedSlot) {
                frameLiftedSlot = trackedSlot
                trackedSlot = NO_SLOT
                trackingId = NO_TRACKING_ID
                signalUp()
            }
            // Otherwise another (ignored) finger lifted.
        } else if (trackedSlot == NO_SLOT) {
            trackedSlot = slot
            trackingId = value
            signalDown()
        } else if (slot == trackedSlot && value != trackingId) {
            // The followed contact was replaced in its slot without a -1: lift + new touch.
            trackingId = value
            signalUp()
            signalDown()
        }
        // Otherwise a second finger in another slot: ignored.
    }

    private fun signalDown() {
        frameSawDown = true
        frameLastSignal = SIGNAL_DOWN
    }

    private fun signalUp() {
        frameSawUp = true
        frameLastSignal = SIGNAL_UP
    }

    /** The slot whose position the followed contact has at the end of this frame. */
    private fun contactSlot(): Int = when {
        !useMtAxes -> 0
        trackedSlot != NO_SLOT -> trackedSlot.coerceIn(0, MAX_SLOTS - 1)
        frameLiftedSlot != NO_SLOT -> frameLiftedSlot.coerceIn(0, MAX_SLOTS - 1)
        else -> slot.coerceIn(0, MAX_SLOTS - 1)
    }

    private fun endFrame(timeMs: Long, sink: (DecodedTouch) -> Unit) {
        val s = contactSlot()
        val x = slotX[s]
        val y = slotY[s]
        val moved = x != lastX || y != lastY
        if (!touching) {
            if (frameSawDown) {
                touching = true
                downTimeMs = timeMs
                emit(TouchAction.DOWN, x, y, timeMs, sink)
                if (frameLastSignal == SIGNAL_UP) {
                    // Down and up inside one frame: a tap shorter than the report interval.
                    touching = false
                    emit(TouchAction.UP, x, y, timeMs, sink)
                }
            }
            // An up with no down (an ignored finger, or a lift after SYN_DROPPED) emits nothing.
        } else when {
            frameLastSignal == SIGNAL_UP -> {
                if (moved) emit(TouchAction.MOVE, x, y, timeMs, sink)
                touching = false
                emit(TouchAction.UP, x, y, timeMs, sink)
            }
            frameLastSignal == SIGNAL_DOWN && frameSawUp -> {
                emit(TouchAction.UP, lastX, lastY, timeMs, sink)
                downTimeMs = timeMs
                emit(TouchAction.DOWN, x, y, timeMs, sink)
            }
            moved -> emit(TouchAction.MOVE, x, y, timeMs, sink)
        }
        clearFrame()
    }

    private fun startDropping(timeMs: Long, sink: (DecodedTouch) -> Unit) {
        cancel(timeMs)?.let(sink)
        dropping = true
    }

    private fun emit(action: TouchAction, x: Int, y: Int, timeMs: Long, sink: (DecodedTouch) -> Unit) {
        lastX = x
        lastY = y
        sink(DecodedTouch(action, x, y, timeMs, downTimeMs, device))
    }

    private fun clearFrame() {
        frameSawDown = false
        frameSawUp = false
        frameLastSignal = SIGNAL_NONE
        frameLiftedSlot = NO_SLOT
    }

    private companion object {
        /** Slots remembered; far more than any keyboard touch surface reports. */
        const val MAX_SLOTS = 16
        const val NO_SLOT = -1
        const val NO_TRACKING_ID = -1
        const val SIGNAL_NONE = 0
        const val SIGNAL_DOWN = 1
        const val SIGNAL_UP = 2
    }
}
