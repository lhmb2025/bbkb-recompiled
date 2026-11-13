package dev.bbkb.ime.core.shared;

import android.util.Log;

import dev.bbkb.ime.BuildConfig;

/**
 * Runtime gate for per-sample input logging (CKB_SWIPE_TYPE_DEBUG, XT9RAW and native trace
 * mirrors), which costs latency on the swipe hot path. Debug builds only; enable with
 * {@code adb shell setprop log.tag.XT9Input VERBOSE} (INFO to disable) and restart the IME, or
 * call {@link #refresh()}.
 */
public final class InputPathDebug {
    public static final String TAG = "XT9Input";

    private static volatile boolean sOn = compute();

    private InputPathDebug() {}

    /**
     * Per-sample diagnostics (touchMove, trace mirrors). Off unless the sysprop is set: their
     * overhead can drop touch samples and corrupt the recognition being diagnosed.
     */
    public static boolean on() {
        return sOn;
    }

    /**
     * Per-gesture diagnostics (one line per swipe), on in every debug build. Not gated on the
     * sysprop, because turning that on perturbs swipe accuracy.
     */
    public static boolean perGesture() {
        return BuildConfig.DEBUG;
    }

    public static void refresh() {
        sOn = compute();
    }

    private static boolean compute() {
        return BuildConfig.DEBUG && Log.isLoggable(TAG, Log.VERBOSE);
    }
}
