package dev.bbkb.ime.core.device.touch.shizuku;

import dev.bbkb.ime.core.device.touch.shizuku.EvdevDeviceInfo;
import dev.bbkb.ime.core.device.touch.shizuku.EvdevOpenResult;
import dev.bbkb.ime.core.device.touch.shizuku.IEvdevCallback;

/**
 * The Shizuku UserService that reads one input device for the IME (EvdevUserService). It runs
 * as shell (adb-started Shizuku) or root (root-started Shizuku) in its own ":evdev" process.
 *
 * Every method has an explicit transaction id because destroy() must: Shizuku sends transaction
 * 16777115 (FIRST_CALL_TRANSACTION + 16777114) when it tears a user service down, and AIDL only
 * accepts explicit ids on all methods or on none.
 */
interface IEvdevService {
    /** Shizuku-reserved: the server calls this before it kills the process. */
    void destroy() = 16777114;

    /** Every /dev/input/event* node this process can open, in node-number order. */
    List<EvdevDeviceInfo> listDevices() = 1;

    /**
     * Opens the first device (in node order) whose name equals `pattern`, or, when `isRegex`,
     * contains a match for it (java.util.regex; anchor with ^...$ for a whole-name match).
     * Replaces any stream already open. On success events flow to `callback` until close(),
     * a newer open(), the device vanishing, or the callback's process dying (which also
     * releases the grab).
     */
    EvdevOpenResult open(String pattern, boolean isRegex, boolean grab, IEvdevCallback callback) = 2;

    /** Takes or releases EVIOCGRAB on the open device. False when nothing is open or it failed. */
    boolean setGrab(boolean grab) = 3;

    /** Stops the stream (releasing any grab) and returns once the device is closed. */
    void close() = 4;
}
