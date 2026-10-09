package dev.bbkb.ime.core.device.touch;

import androidx.annotation.NonNull;

/**
 * A way for the touch keypad's events to reach the IME's gesture pipeline
 * ({@code BlackBerryIME.onGenericMotionEvent}). Every source delivers the same MotionEvents the
 * KEY2's pad already produces — the pad's device id (or a registered synthetic one, see
 * {@link SyntheticTouchSources}), {@code SOURCE_TOUCHPAD}, sensor coordinates in the
 * {@link TouchKeypadGeometry} frame — so nothing downstream knows which source fed it.
 *
 * <p>Implementations: {@link NativeTouchSource} (the OS delivers the pad to a focused IME window).
 * The privileged reader for firmware that does not is a separate component;
 * {@link TouchSourceSelector} decides which one should run.
 */
public interface KeypadTouchSource {

    /** Told whenever the source's {@link #status()} changes. Called on the main thread. */
    interface StatusListener {
        void onStatusChanged(@NonNull KeypadTouchSource source, @NonNull TouchSourceStatus status);
    }

    /** Allow the source to run (it may still wait for the IME window). */
    void start();

    /** Stop delivering events and release whatever the source holds. */
    void stop();

    /** Where the source stands right now. */
    @NonNull
    TouchSourceStatus status();

    void addStatusListener(@NonNull StatusListener listener);

    void removeStatusListener(@NonNull StatusListener listener);
}
