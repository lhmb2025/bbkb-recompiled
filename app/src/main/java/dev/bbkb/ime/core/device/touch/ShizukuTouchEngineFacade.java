package dev.bbkb.ime.core.device.touch;

import android.content.Context;

import androidx.annotation.NonNull;

import dev.bbkb.ime.core.device.touch.shizuku.ShizukuTouchEngine;
import dev.bbkb.ime.core.device.touch.shizuku.ShizukuTouchStatus;
import dev.bbkb.ime.core.device.touch.shizuku.TouchDeviceMatcher;

/**
 * The slice of {@link ShizukuTouchEngine} the touch sources use, as an interface so JVM tests can
 * fake it. Main thread only; listeners are called on it.
 */
public interface ShizukuTouchEngineFacade {
    /** Replaces any stream already started. */
    void start(@NonNull Context context, @NonNull TouchDeviceMatcher matcher, boolean grab,
               @NonNull ShizukuTouchEngine.TouchListener touches,
               @NonNull ShizukuTouchEngine.StatusListener statuses);

    void setGrab(boolean grab);

    /** The {@link #start} listeners hear nothing more. */
    void stop();

    @NonNull
    ShizukuTouchStatus status();

    /** An install or a start of Shizuku sends the app no event, hence this. */
    void refreshStatus(@NonNull Context context);

    /** False when there is nothing to ask or Shizuku would not ask. */
    boolean requestPermission(@NonNull Context context);

    /** Hears the current status first. */
    void addStatusListener(@NonNull Context context,
                           @NonNull ShizukuTouchEngine.StatusListener listener);

    void removeStatusListener(@NonNull ShizukuTouchEngine.StatusListener listener);

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
