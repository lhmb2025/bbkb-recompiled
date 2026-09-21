package dev.bbkb.ime.core.shared;

import android.util.Log;

import dev.bbkb.ime.BuildConfig;

/**
 * Runtime gate for per-keystroke / per-swipe diagnostic logging (the
 * CKB_SWIPE_TYPE_DEBUG and XT9RAW channels plus their native trace mirrors).
 *
 * These logs fire on every touch sample and keypress. Gating them on
 * BuildConfig.DEBUG alone leaves them live on daily-driver debug builds, which
 * is measurable latency on the swipe hot path. This gate keeps them one adb
 * command away instead:
 *
 *   adb shell setprop log.tag.XT9Input VERBOSE     # enable
 *   adb shell setprop log.tag.XT9Input INFO        # disable
 *
 * then restart the IME process (the value is cached at class load; call
 * {@link #refresh()} from a debug hook to re-read it without a restart).
 * Release builds are always off regardless of the sysprop.
 */
public final class InputPathDebug {

    /** Sysprop tag checked via {@code log.tag.XT9Input}. */
    public static final String TAG = "XT9Input";

    private static volatile boolean sOn = compute();

    private InputPathDebug() {}

    /**
     * True when PER-SAMPLE input-path diagnostics should log (touchMove, trace mirrors). These
     * fire dozens of times per gesture and their overhead can drop touch samples outright, which
     * corrupts the very recognition they are meant to diagnose. Off unless the sysprop is set.
     */
    public static boolean on() {
        return sOn;
    }

    /**
     * True for PER-GESTURE diagnostics — one line per swipe (touchdown key resolution, the
     * engine's recognised word). Cheap enough to leave on in every debug build, and it is what
     * makes a "wrong word" report actionable without asking the user to re-run with logging on.
     * Deliberately NOT gated on the sysprop: enabling that to investigate a recognition problem
     * perturbs the result (2026-08-12 — swipe accuracy changed between logging states).
     */
    public static boolean perGesture() {
        return BuildConfig.DEBUG;
    }

    /** Re-read the sysprop without restarting the IME process. */
    public static void refresh() {
        sOn = compute();
    }

    private static boolean compute() {
        return BuildConfig.DEBUG && Log.isLoggable(TAG, Log.VERBOSE);
    }
}
