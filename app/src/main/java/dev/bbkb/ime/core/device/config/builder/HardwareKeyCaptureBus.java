package dev.bbkb.ime.core.device.config.builder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Routes raw hardware key events to the capture screen. A key can surface in the accessibility
 * service ({@code KeyInterceptorService}, which consumes board keys like Sym), the IME key path,
 * or the foreground activity, so each calls {@link #offer}; while a listener is registered the
 * event is consumed, not acted on (no symbol board, no sticky Alt). Static and single-listener
 * because the producers share no object and only one capture screen can be in front.
 */
public final class HardwareKeyCaptureBus {

    /** Receives raw hardware key events while a capture screen is open. */
    public interface Listener {
        /**
         * @param keyCode     the Android keycode the ROM's keylayout attached
         * @param down        true for {@code ACTION_DOWN}, false for {@code ACTION_UP}
         * @return true if the event was used and must not travel any further
         */
        boolean onHardwareKey(int scanCode, int keyCode, int deviceId, int repeatCount,
                              boolean down, long eventTime);
    }

    @Nullable private static volatile Listener sListener;

    private HardwareKeyCaptureBus() {}

    /** Replaces any previous listener. */
    public static void register(@NonNull Listener listener) {
        sListener = listener;
    }

    /** Removes {@code listener} only if still installed, so a late dispose cannot unhook its replacement. */
    public static void unregister(@NonNull Listener listener) {
        if (sListener == listener) {
            sListener = null;
        }
    }

    /** True while a capture screen is open; one volatile read otherwise. */
    public static boolean isCapturing() {
        return sListener != null;
    }

    /** Returns true if capture consumed the event; the caller must then not process it further. */
    public static boolean offer(int scanCode, int keyCode, int deviceId, int repeatCount,
                                boolean down, long eventTime) {
        final Listener listener = sListener;
        if (listener == null) {
            return false;
        }
        return listener.onHardwareKey(scanCode, keyCode, deviceId, repeatCount, down, eventTime);
    }
}
