package com.blackberry.nuanceshim;

import android.content.Context;
import android.util.Log;

/**
 * Debug-only glue for the owned ET9KDB module (libxt9kdb.so, exports xt9kdb_*): parses a KDB asset
 * with the owned Load_XmlKDB and logs the model. Never calls the blob or changes recognition.
 */
public final class Xt9Kdb {
    private static final String TAG = "XT9KDB";
    private static boolean sLoaded;

    static {
        try {
            System.loadLibrary("xt9kdb");
            sLoaded = true;
        } catch (Throwable t) {
            Log.w(TAG, "libxt9kdb load failed: " + t.getMessage());
        }
    }

    private Xt9Kdb() {}

    public static native int nativeInit();
    public static native int nativeLoadKdb(byte[] xml);

    /* Mirrors raw sensor swipe points into the owned trace on a scratch context. */
    private static native void nativeTraceStart(float x, float y, long t);
    private static native void nativeTraceMove(float x, float y, long t);
    private static native void nativeTraceEnd(float x, float y, long t);

    /* Tap calibration: resolve a single tap through the owned model and log it (XT9TAP). */
    private static native void nativeLogTap(float x, float y);

    public static boolean isLoaded() { return sLoaded; }

    /** Log how the OWNED model resolves a tap at raw sensor (x,y). View: adb logcat -s XT9TAP:I */
    public static void logTap(float x, float y) { if (sLoaded) try { nativeLogTap(x, y); } catch (Throwable ignored) { /* debug only */ } }

    /**
     * Feeds the raw sensor points NuanceSDK sends the blob into the owned decoder on a scratch context.
     * traceEnd logs the owned candidates under XT9OWNED. No-ops if the owned lib failed to load.
     */
    public static void traceStart(float x, float y, long t) { if (sLoaded) try { nativeTraceStart(x, y, t); } catch (Throwable ignored) { /* debug only */ } }
    public static void traceMove(float x, float y, long t)  { if (sLoaded) try { nativeTraceMove(x, y, t);  } catch (Throwable ignored) { /* debug only */ } }
    public static void traceEnd(float x, float y, long t)   { if (sLoaded) try { nativeTraceEnd(x, y, t);   } catch (Throwable ignored) { /* debug only */ } }

    /** Load the device's keypad geometry (kdb/keypad_pkb_&lt;variant&gt;.xml) into the owned module and log the parsed model. */
    public static void selfCheck(Context ctx, String variant) {
        if (!sLoaded || ctx == null) return;
        final String path = "kdb/" + dev.bbkb.ime.core.layout.KeypadKdbs.templateFile(variant);
        try {
            final byte[] xml = Xt9KdbVariant.readAsset(ctx.getAssets(), path);
            if (xml == null) return;
            nativeInit();
            int kc = nativeLoadKdb(xml);
            Log.i(TAG, "owned self-check (" + path + "): loaded " + kc + " keys");
        } catch (Throwable t) {
            Log.w(TAG, "owned self-check failed for " + path + ": " + t.getMessage());
        }
    }
}
