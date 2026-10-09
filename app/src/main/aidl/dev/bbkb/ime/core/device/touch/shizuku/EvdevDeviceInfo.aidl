package dev.bbkb.ime.core.device.touch.shizuku;

/**
 * One /dev/input/event* node as EvdevUserService saw it. Mirrors TouchDeviceInfo, the
 * Android-free model the IME side works with (EvdevDeviceInfos converts between the two).
 *
 * `flags` uses the EvdevProbeLayout.FLAG_* bits: which of the axes below exist, and whether
 * the device reports BTN_TOUCH, ABS_MT_TRACKING_ID and ABS_MT_SLOT. A range is only meaningful
 * when its flag is set.
 */
parcelable EvdevDeviceInfo {
    String path;
    String name;
    int flags;
    int absXMin;
    int absXMax;
    int absYMin;
    int absYMax;
    int mtXMin;
    int mtXMax;
    int mtYMin;
    int mtYMax;
    int mtSlotMax;
    /** Only on an open() result: whether this session holds the EVIOCGRAB exclusive grab. */
    boolean grabbed;
}
