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
 * One touch in the device's raw coordinates (scale by [device]'s ranges). Times are on the
 * SystemClock.uptimeMillis() timebase, ready for MotionEvent.obtain().
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
 * Turns packed evdev records ([EvdevPacking]) into DOWN/MOVE/UP/CANCEL for one contact. Pure
 * Kotlin, so every rule has a JVM test.
 *  - BTN_TOUCH and ABS_MT_TRACKING_ID both mean down/up; a tracking id changing without -1 is a
 *    lift plus a new touch.
 *  - Position: ABS_MT_POSITION_X/Y when present, else ABS_X/Y; last value remembered per slot.
 *  - Only the first contact is followed; touch size and pressure are ignored (on the Titan pad a
 *    second finger shows up as a size jump, not a new contact).
 *  - Emits only at SYN_REPORT; MOVE only on a real change. SYN_DROPPED CANCELs, discards up to the
 *    next SYN_REPORT, and the finger that was down stays ignored until a fresh touch-down.
 *  - Stamps are CLOCK_MONOTONIC micros ([EvdevUserService]), the same clock as uptimeMillis(), so
 *    `eventTimeMs = micros / 1000` needs no offset.
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

    /** The stream ended: the CANCEL at [timeMs] to deliver, or null if nothing was down. */
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
