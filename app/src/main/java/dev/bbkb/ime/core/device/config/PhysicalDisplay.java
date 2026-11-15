package dev.bbkb.ime.core.device.config;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;

/**
 * Physical panel resolution for device-config matching, formatted "{@code <short>x<long>}" (for
 * example "720x720").
 *
 * <p>The value is read from the display's supported hardware modes
 * ({@link Display#getSupportedModes()} with {@link Display.Mode#getPhysicalWidth()}), which report
 * the panel's own resolution. A forced logical size (a {@code wm size} override) rewrites the
 * logical metrics, not the hardware mode list, so a mode-derived value is unaffected by software
 * scaling. The mode of greatest area is used, so the native panel resolution is reported even when
 * the active mode is a smaller entry of a resolution switcher. Orientation is normalised to
 * short-side-by-long-side, so a match does not depend on the display's native rotation. The scaled
 * {@link DisplayMetrics} is the fallback for a display that reports no mode.
 *
 * <p>Read by {@link dev.bbkb.ime.core.device.config.model.DeviceMatchCriteria} the same way its
 * other criteria read {@code Build} fields. The result is cached after the first read; the panel's
 * mode list does not change across a resolution switch (every mode stays listed), so the cached
 * value is stable.
 */
public final class PhysicalDisplay {

    private static volatile Context sAppContext;
    private static volatile String sCached;

    private PhysicalDisplay() {
    }

    /** Records the application context used by the first {@link #resolution()} read. */
    public static void attach(Context context) {
        if (context != null && sAppContext == null) {
            sAppContext = context.getApplicationContext();
        }
    }

    /**
     * The panel resolution as "{@code <short>x<long>}", or null when no context has been attached or
     * no resolution can be read. The physical mode is preferred; the scaled metrics are the fallback.
     */
    public static String resolution() {
        final String cached = sCached;
        if (cached != null) {
            return cached;
        }
        final Context context = sAppContext;
        if (context == null) {
            return null;
        }
        String value = fromPhysicalModes(context);
        if (value == null) {
            value = fromScaledMetrics(context);
        }
        sCached = value;
        return value;
    }

    private static String fromPhysicalModes(Context context) {
        final DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            return null;
        }
        final Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) {
            return null;
        }
        final Display.Mode[] modes = display.getSupportedModes();
        if (modes == null || modes.length == 0) {
            return null;
        }
        int bestWidth = 0;
        int bestHeight = 0;
        long bestArea = -1L;
        for (Display.Mode mode : modes) {
            final int width = mode.getPhysicalWidth();
            final int height = mode.getPhysicalHeight();
            final long area = (long) width * height;
            if (width > 0 && height > 0 && area > bestArea) {
                bestArea = area;
                bestWidth = width;
                bestHeight = height;
            }
        }
        if (bestArea <= 0L) {
            return null;
        }
        return normalise(bestWidth, bestHeight);
    }

    private static String fromScaledMetrics(Context context) {
        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        if (metrics == null || metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            return null;
        }
        return normalise(metrics.widthPixels, metrics.heightPixels);
    }

    private static String normalise(int a, int b) {
        return Math.min(a, b) + "x" + Math.max(a, b);
    }
}
