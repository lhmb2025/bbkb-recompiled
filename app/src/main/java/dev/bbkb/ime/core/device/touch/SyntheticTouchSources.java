package dev.bbkb.ime.core.device.touch;

import java.util.Arrays;

/**
 * Device ids that a touch source stamps on MotionEvents it synthesises (a privileged evdev reader
 * has no InputDevice of its own to borrow). {@code DeviceProfile.isFromTouchKeypad} accepts events
 * carrying a registered id, and a registered source counts toward {@code hasTouchKeypad()}.
 *
 * <p>A source registers its id when it starts delivering and unregisters it when it stops. The
 * set is tiny (one source at a time in practice) and read on every keypad MotionEvent, so it is an
 * immutable array swapped on write.
 */
public final class SyntheticTouchSources {

    private static volatile int[] sIds = new int[0];

    private SyntheticTouchSources() {}

    public static synchronized void register(int deviceId) {
        if (contains(deviceId)) return;
        final int[] next = Arrays.copyOf(sIds, sIds.length + 1);
        next[next.length - 1] = deviceId;
        sIds = next;
    }

    public static synchronized void unregister(int deviceId) {
        final int[] ids = sIds;
        int n = 0;
        final int[] next = new int[ids.length];
        for (int id : ids) {
            if (id != deviceId) next[n++] = id;
        }
        sIds = Arrays.copyOf(next, n);
    }

    public static boolean contains(int deviceId) {
        for (int id : sIds) {
            if (id == deviceId) return true;
        }
        return false;
    }

    public static boolean isEmpty() {
        return sIds.length == 0;
    }

    /** Drop every registration (tests). */
    public static synchronized void clear() {
        sIds = new int[0];
    }
}
