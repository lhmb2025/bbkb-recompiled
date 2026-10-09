package dev.bbkb.ime.core.device.touch;

import android.content.Context;

import androidx.annotation.NonNull;

import dev.bbkb.ime.core.device.touch.shizuku.ShizukuTouchEngine;
import dev.bbkb.ime.core.device.touch.shizuku.ShizukuTouchStatus;
import dev.bbkb.ime.core.device.touch.shizuku.TouchDeviceMatcher;

/**
 * The slice of {@link ShizukuTouchEngine} that {@link ShizukuTouchSource} and
 * {@link KeypadTouchSources} use, behind an interface so both run against a fake on the JVM.
 * {@link #REAL} is the engine itself; every method has the engine's meaning and threading (call
 * on the main thread; listeners are called on it).
 */
public interface ShizukuTouchEngineFacade {

    /** Stream the device {@code matcher} names, replacing any stream already started. */
    void start(@NonNull Context context, @NonNull TouchDeviceMatcher matcher, boolean grab,
               @NonNull ShizukuTouchEngine.TouchListener touches,
               @NonNull ShizukuTouchEngine.StatusListener statuses);

    /** Take or release the exclusive grab on the running stream. */
    void setGrab(boolean grab);

    /** Stop the stream; the {@link #start} listeners hear nothing more. */
    void stop();

    @NonNull
    ShizukuTouchStatus status();

    /** Re-read Shizuku's own state (an install or a start sends the app no event). */
    void refreshStatus(@NonNull Context context);

    /** Ask Shizuku for access; false when there is nothing to ask or it would not ask. */
    boolean requestPermission(@NonNull Context context);

    /** Hear every status change, starting with the current one. */
    void addStatusListener(@NonNull Context context,
                           @NonNull ShizukuTouchEngine.StatusListener listener);

    void removeStatusListener(@NonNull ShizukuTouchEngine.StatusListener listener);

    /** The engine. */
    ShizukuTouchEngineFacade REAL = new ShizukuTouchEngineFacade() {
        @Override
        public void start(@NonNull Context context, @NonNull TouchDeviceMatcher matcher, boolean grab,
                          @NonNull ShizukuTouchEngine.TouchListener touches,
                          @NonNull ShizukuTouchEngine.StatusListener statuses) {
            ShizukuTouchEngine.start(context, matcher, grab, touches, statuses, null);
        }

        @Override
        public void setGrab(boolean grab) {
            ShizukuTouchEngine.setGrab(grab);
        }

        @Override
        public void stop() {
            ShizukuTouchEngine.stop();
        }

        @NonNull
        @Override
        public ShizukuTouchStatus status() {
            return ShizukuTouchEngine.getStatus();
        }

        @Override
        public void refreshStatus(@NonNull Context context) {
            ShizukuTouchEngine.refreshStatus(context);
        }

        @Override
        public boolean requestPermission(@NonNull Context context) {
            return ShizukuTouchEngine.requestPermission(context);
        }

        @Override
        public void addStatusListener(@NonNull Context context,
                                      @NonNull ShizukuTouchEngine.StatusListener listener) {
            ShizukuTouchEngine.addStatusListener(context, listener);
        }

        @Override
        public void removeStatusListener(@NonNull ShizukuTouchEngine.StatusListener listener) {
            ShizukuTouchEngine.removeStatusListener(listener);
        }
    };
}
