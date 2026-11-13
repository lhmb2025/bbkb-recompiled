package dev.bbkb.ime.core.device.touch.shizuku;

/**
 * One /dev/input/event* node as EvdevUserService saw it; EvdevDeviceInfos converts it to TouchDeviceInfo.
 * `flags` uses the EvdevProbeLayout.FLAG_* bits: which axes below exist (a range is only meaningful
 * when its flag is set) and whether BTN_TOUCH, ABS_MT_TRACKING_ID and ABS_MT_SLOT are reported.
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
