package dev.bbkb.ime.core.device.touch.shizuku

/** An evdev axis range as EVIOCGABS reports it (inclusive at both ends). */
data class AxisRange(val min: Int, val max: Int) {
    val span: Int get() = max - min
    override fun toString(): String = "$min..$max"
}

/**
 * One input device as the Shizuku touch engine sees it, free of Android and AIDL types so the
 * decoder, the matcher and the tests can use it directly.
 *
 * Touch position comes from the multi-touch axes when the device has both of them, otherwise
 * from ABS_X/ABS_Y; [xRange]/[yRange] are the ranges of whichever pair [TouchStreamDecoder]
 * reads, which is what a consumer scaling raw coordinates needs.
 */
data class TouchDeviceInfo(
    /** The node it was read from, e.g. `/dev/input/event3`. Changes across re-enumeration. */
    val path: String,
    /** EVIOCGNAME, e.g. `touch_keypad` (KEY2) or `touchPad` (Titan 2). The stable identity. */
    val name: String,
    val absX: AxisRange? = null,
    val absY: AxisRange? = null,
    val mtX: AxisRange? = null,
    val mtY: AxisRange? = null,
    val hasBtnTouch: Boolean = false,
    val hasTrackingId: Boolean = false,
    /** ABS_MT_SLOT's max + 1, or 0 for a device without slots (protocol A or single touch). */
    val mtSlots: Int = 0,
    /** Only meaningful on the device of a running stream: whether it holds the EVIOCGRAB grab. */
    val grabbed: Boolean = false,
) {
    val usesMtAxes: Boolean get() = mtX != null && mtY != null
    val xRange: AxisRange? get() = if (usesMtAxes) mtX else absX
    val yRange: AxisRange? get() = if (usesMtAxes) mtY else absY

    /** Whether there is any position to decode at all (a plain key device has none). */
    val hasPosition: Boolean get() = xRange != null && yRange != null

    /** One log-friendly line, used by the debug probe and the status. */
    fun describe(): String = buildString {
        append(path).append(" '").append(name).append('\'')
        mtX?.let { append(" mtX=").append(it) }
        mtY?.let { append(" mtY=").append(it) }
        absX?.let { append(" absX=").append(it) }
        absY?.let { append(" absY=").append(it) }
        if (mtSlots > 0) append(" slots=").append(mtSlots)
        if (hasTrackingId) append(" trackingId")
        if (hasBtnTouch) append(" btnTouch")
        if (grabbed) append(" grabbed")
    }
}

/**
 * The int[] the native probe (`nativeProbe` in bbkbevdev.c) fills for one node, and its bits.
 * The C side mirrors these numbers; [toDeviceInfo] is the only reader.
 */
object EvdevProbeLayout {
    /** Flags (>= 0) on success, or a negated errno when the node could not be probed. */
    const val STATUS = 0
    const val ABS_X_MIN = 1
    const val ABS_X_MAX = 2
    const val ABS_Y_MIN = 3
    const val ABS_Y_MAX = 4
    const val MT_X_MIN = 5
    const val MT_X_MAX = 6
    const val MT_Y_MIN = 7
    const val MT_Y_MAX = 8
    const val MT_SLOT_MAX = 9
    const val SIZE = 10

    const val FLAG_ABS_X = 1
    const val FLAG_ABS_Y = 1 shl 1
    const val FLAG_MT_X = 1 shl 2
    const val FLAG_MT_Y = 1 shl 3
    const val FLAG_BTN_TOUCH = 1 shl 4
    const val FLAG_MT_TRACKING_ID = 1 shl 5
    const val FLAG_MT_SLOT = 1 shl 6

    /** The device a successful probe describes, or null when the probe failed. */
    @JvmStatic
    fun toDeviceInfo(path: String, name: String?, probe: IntArray): TouchDeviceInfo? {
        if (name == null || probe.size < SIZE) return null
        val flags = probe[STATUS]
        if (flags < 0) return null
        return fromFlags(
            path, name, flags,
            probe[ABS_X_MIN], probe[ABS_X_MAX], probe[ABS_Y_MIN], probe[ABS_Y_MAX],
            probe[MT_X_MIN], probe[MT_X_MAX], probe[MT_Y_MIN], probe[MT_Y_MAX],
            probe[MT_SLOT_MAX], grabbed = false,
        )
    }

    @JvmStatic
    fun fromFlags(
        path: String, name: String, flags: Int,
        absXMin: Int, absXMax: Int, absYMin: Int, absYMax: Int,
        mtXMin: Int, mtXMax: Int, mtYMin: Int, mtYMax: Int,
        mtSlotMax: Int, grabbed: Boolean,
    ): TouchDeviceInfo = TouchDeviceInfo(
        path = path,
        name = name,
        absX = if (flags and FLAG_ABS_X != 0) AxisRange(absXMin, absXMax) else null,
        absY = if (flags and FLAG_ABS_Y != 0) AxisRange(absYMin, absYMax) else null,
        mtX = if (flags and FLAG_MT_X != 0) AxisRange(mtXMin, mtXMax) else null,
        mtY = if (flags and FLAG_MT_Y != 0) AxisRange(mtYMin, mtYMax) else null,
        hasBtnTouch = flags and FLAG_BTN_TOUCH != 0,
        hasTrackingId = flags and FLAG_MT_TRACKING_ID != 0,
        mtSlots = if (flags and FLAG_MT_SLOT != 0) mtSlotMax + 1 else 0,
        grabbed = grabbed,
    )

    @JvmStatic
    fun flagsOf(info: TouchDeviceInfo): Int {
        var flags = 0
        if (info.absX != null) flags = flags or FLAG_ABS_X
        if (info.absY != null) flags = flags or FLAG_ABS_Y
        if (info.mtX != null) flags = flags or FLAG_MT_X
        if (info.mtY != null) flags = flags or FLAG_MT_Y
        if (info.hasBtnTouch) flags = flags or FLAG_BTN_TOUCH
        if (info.hasTrackingId) flags = flags or FLAG_MT_TRACKING_ID
        if (info.mtSlots > 0) flags = flags or FLAG_MT_SLOT
        return flags
    }
}
