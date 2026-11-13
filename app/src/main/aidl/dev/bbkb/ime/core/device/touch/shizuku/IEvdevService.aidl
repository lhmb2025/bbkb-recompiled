package dev.bbkb.ime.core.device.touch.shizuku;

import dev.bbkb.ime.core.device.touch.shizuku.EvdevDeviceInfo;
import dev.bbkb.ime.core.device.touch.shizuku.EvdevOpenResult;
import dev.bbkb.ime.core.device.touch.shizuku.IEvdevCallback;

/**
 * The Shizuku UserService (EvdevUserService) that reads one input device for the IME, as shell or
 * root in its own ":evdev" process. Every method has an explicit transaction id because destroy()
 * must (Shizuku sends 16777115 = FIRST_CALL_TRANSACTION + 16777114) and AIDL wants all or none.
 */
interface IEvdevService {
    /** Shizuku-reserved: the server calls this before it kills the process. */
    void destroy() = 16777114;

    /** Every /dev/input/event* node this process can open, in node-number order. */
    List<EvdevDeviceInfo> listDevices() = 1;

    /**
     * Opens the first device (node order) whose name equals `pattern`, or with `isRegex` contains a
     * match (anchor with ^...$). Replaces any open stream; events flow to `callback` until close(),
     * a newer open(), the device vanishing, or the callback's process dying (which releases the grab).
     */
    EvdevOpenResult open(String pattern, boolean isRegex, boolean grab, IEvdevCallback callback) = 2;

    /** Takes or releases EVIOCGRAB on the open device. False when nothing is open or it failed. */
    boolean setGrab(boolean grab) = 3;

    /** Stops the stream (releasing any grab) and returns once the device is closed. */
    void close() = 4;
}
