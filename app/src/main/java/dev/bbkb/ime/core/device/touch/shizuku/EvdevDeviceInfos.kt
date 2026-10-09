package dev.bbkb.ime.core.device.touch.shizuku

/** [TouchDeviceInfo] -> the AIDL parcelable [EvdevDeviceInfo] (reader side). */
internal fun TouchDeviceInfo.toParcel(): EvdevDeviceInfo = EvdevDeviceInfo().also { p ->
    p.path = path
    p.name = name
    p.flags = EvdevProbeLayout.flagsOf(this)
    absX?.let { p.absXMin = it.min; p.absXMax = it.max }
    absY?.let { p.absYMin = it.min; p.absYMax = it.max }
    mtX?.let { p.mtXMin = it.min; p.mtXMax = it.max }
    mtY?.let { p.mtYMin = it.min; p.mtYMax = it.max }
    p.mtSlotMax = if (mtSlots > 0) mtSlots - 1 else 0
    p.grabbed = grabbed
}

/** The AIDL parcelable [EvdevDeviceInfo] -> [TouchDeviceInfo] (IME side). */
internal fun EvdevDeviceInfo.toTouchDeviceInfo(): TouchDeviceInfo = EvdevProbeLayout.fromFlags(
    path ?: "", name ?: "", flags,
    absXMin, absXMax, absYMin, absYMax,
    mtXMin, mtXMax, mtYMin, mtYMax,
    mtSlotMax, grabbed,
)
