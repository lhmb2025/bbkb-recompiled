package dev.bbkb.ime.core.device.touch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.bbkb.ime.core.device.detection.TouchKeypadInfo;

import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Device ids that a touch source stamps on MotionEvents it synthesises (a privileged evdev reader
 * has no InputDevice of its own to borrow), with the pad ranges that source measured.
 * {@code DeviceProfile.isFromTouchKeypad} accepts events carrying a registered id, a registered
 * source counts toward {@code hasTouchKeypad()}, and the measured ranges are
 * {@link TouchKeypadGeometry}'s top-precedence frame (see {@link #measuredPad()}).
 *
 * <p>A source registers its id when it starts delivering and unregisters it when it stops. The
 * set is tiny (one source at a time in practice) and read on every keypad MotionEvent, so it is an
 * immutable array swapped on write. {@link Listener}s hear about every change, on the thread that
 * made it.
 */
public final class SyntheticTouchSources {

    /** Told after every registration change. */
    public interface Listener {
        void onSyntheticTouchSourcesChanged();
    }

    private static volatile TouchKeypadInfo[] sPads = new TouchKeypadInfo[0];
    private static final CopyOnWriteArrayList<Listener> sListeners = new CopyOnWriteArrayList<>();

    private SyntheticTouchSources() {}

    /** Register a source by its id alone: it measured nothing, so the profile's frame applies. */
    public static void register(int deviceId) {
        register(TouchKeypadInfo.measured(deviceId, 0f, 0f));
    }

    /**
     * Register a source with the pad ranges it measured. Registering an id again replaces its
     * ranges; registering the same facts again changes nothing and notifies no one.
     */
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
     * The first registered source that measured both of the pad's ranges, or null. The same
     * instance comes back until the registrations change, so a cache keyed on it stays valid
     * exactly as long as its answer does.
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

    /** Drop every registration (tests). */
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
