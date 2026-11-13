package dev.bbkb.ime.core.device.touch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.bbkb.ime.core.device.detection.TouchKeypadInfo;

import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Device ids stamped on synthesised MotionEvents (an evdev reader has no InputDevice), with the
 * ranges each source measured. Registered ids pass {@code isFromTouchKeypad}, count toward
 * {@code hasTouchKeypad()}, and give {@link TouchKeypadGeometry} its top-precedence frame.
 * Read on every keypad event, so it is an immutable array swapped on write; listeners run on the
 * writing thread.
 */
public final class SyntheticTouchSources {
    public interface Listener {
        void onSyntheticTouchSourcesChanged();
    }

    private static volatile TouchKeypadInfo[] sPads = new TouchKeypadInfo[0];
    private static final CopyOnWriteArrayList<Listener> sListeners = new CopyOnWriteArrayList<>();

    private SyntheticTouchSources() {}

    /** Registers an id with no measured ranges, so the profile's frame applies. */
    public static void register(int deviceId) {
        register(TouchKeypadInfo.measured(deviceId, 0f, 0f));
    }

    /** Re-registering an id replaces its ranges; identical facts notify no one. */
    public static void register(@NonNull TouchKeypadInfo pad) {
        synchronized (SyntheticTouchSources.class) {
            final TouchKeypadInfo[] pads = sPads;
            final int i = indexOf(pads, pad.getDeviceId());
            if (i >= 0 && pads[i].sameAs(pad)) return;
            final TouchKeypadInfo[] next;
            if (i >= 0) {
                next = pads.clone();
                next[i] = pad;
            } else {
                next = Arrays.copyOf(pads, pads.length + 1);
                next[next.length - 1] = pad;
            }
            sPads = next;
        }
        notifyListeners();
    }

    public static void unregister(int deviceId) {
        synchronized (SyntheticTouchSources.class) {
            final TouchKeypadInfo[] pads = sPads;
            if (indexOf(pads, deviceId) < 0) return;
            final TouchKeypadInfo[] next = new TouchKeypadInfo[pads.length - 1];
            int n = 0;
            for (TouchKeypadInfo pad : pads) {
                if (pad.getDeviceId() != deviceId) next[n++] = pad;
            }
            sPads = next;
        }
        notifyListeners();
    }

    public static boolean contains(int deviceId) {
        return indexOf(sPads, deviceId) >= 0;
    }

    public static boolean isEmpty() {
        return sPads.length == 0;
    }

    /**
     * The first source that measured both ranges, or null. Same instance until registrations
     * change, so callers may cache on its identity.
     */
    @Nullable
    public static TouchKeypadInfo measuredPad() {
        for (TouchKeypadInfo pad : sPads) {
            if (pad.getXRangeMax() > 0f && pad.getYRangeMax() > 0f) return pad;
        }
        return null;
    }

    public static void addListener(@NonNull Listener listener) {
        sListeners.addIfAbsent(listener);
    }

    public static void removeListener(@NonNull Listener listener) {
        sListeners.remove(listener);
    }

    /** For tests. */
    public static void clear() {
        synchronized (SyntheticTouchSources.class) {
            if (sPads.length == 0) return;
            sPads = new TouchKeypadInfo[0];
        }
        notifyListeners();
    }

    private static int indexOf(TouchKeypadInfo[] pads, int deviceId) {
        for (int i = 0; i < pads.length; i++) {
            if (pads[i].getDeviceId() == deviceId) return i;
        }
        return -1;
    }

    private static void notifyListeners() {
        for (Listener listener : sListeners) {
            listener.onSyntheticTouchSourcesChanged();
        }
    }
}
