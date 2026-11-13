package dev.bbkb.ime.core.device.touch;

import androidx.annotation.NonNull;

/**
 * A route for touch keypad events into {@code BlackBerryIME.onGenericMotionEvent}. Every source
 * delivers KEY2-shaped events (pad or {@link SyntheticTouchSources} id, {@code SOURCE_TOUCHPAD},
 * {@link TouchKeypadGeometry} sensor coordinates), so downstream cannot tell them apart.
 * {@link TouchSourceSelector} picks {@link NativeTouchSource} or {@link ShizukuTouchSource}.
 */
public interface KeypadTouchSource {
    /** Called on the main thread. */
    interface StatusListener {
        void onStatusChanged(@NonNull KeypadTouchSource source, @NonNull TouchSourceStatus status);
    }

    /** Allows the source to run; it may still wait for the IME window. */
    void start();

    void stop();

    @NonNull
    TouchSourceStatus status();

    void addStatusListener(@NonNull StatusListener listener);

    void removeStatusListener(@NonNull StatusListener listener);
}
