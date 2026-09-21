package com.blackberry.nuanceshim;

import android.content.Context;
import android.util.Log;

/**
 * W1 glue for the OWNED ET9KDB module (libxt9kdb.so, DIFF mode -> exports xt9kdb_*).
 *
 * Debug-only and inert with respect to the live keyboard: it parses a KDB asset through the owned
 * Load_XmlKDB and logs the resulting model, so we can confirm the owned parser/geometry on real
 * hardware. It does NOT call into the blob and does NOT change recognition. Nothing here touches the
 * NuanceSDK path.
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

    /* W3a: mirror raw SENSOR swipe points into the owned trace on a scratch context. */
    private static native void nativeTraceStart(float x, float y, long t);
    private static native void nativeTraceMove(float x, float y, long t);
    private static native void nativeTraceEnd(float x, float y, long t);

    /* Tap calibration: resolve a single tap through the owned model and log it (XT9TAP). */
    private static native void nativeLogTap(float x, float y);

    public static boolean isLoaded() { return sLoaded; }

    /** Log how the OWNED model resolves a tap at raw sensor (x,y). View: adb logcat -s XT9TAP:I */
    public static void logTap(float x, float y) { if (sLoaded) try { nativeLogTap(x, y); } catch (Throwable ignored) {} }

    /**
     * W3a — feed the same raw sensor points NuanceSDK sends the blob into the OWNED gesture decoder,
     * on an isolated scratch context (the owned trace uses its own buffers; the blob is untouched).
     * traceEnd logs the owned candidate sets under tag XT9OWNED, to diff against the blob's recognized
     * list. All three are no-ops (caught) if the owned lib failed to load.
     */
    public static void traceStart(float x, float y, long t) { if (sLoaded) try { nativeTraceStart(x, y, t); } catch (Throwable ignored) {} }
    public static void traceMove(float x, float y, long t)  { if (sLoaded) try { nativeTraceMove(x, y, t);  } catch (Throwable ignored) {} }
    public static void traceEnd(float x, float y, long t)   { if (sLoaded) try { nativeTraceEnd(x, y, t);   } catch (Throwable ignored) {} }

    /** Load kdb/&lt;variant&gt;/qwerty_pkb.xml into the owned module and log the parsed model. */
    public static void selfCheck(Context ctx, String variant) {
        if (!sLoaded || ctx == null) return;
        final String path = "kdb/" + variant + "/qwerty_pkb.xml";
        try {
            // Xt9KdbVariant.readAsset is the same loop with try-with-resources; the copy that
            // used to live here closed the stream only on the success path, so a read failure
            // leaked the asset fd into the outer catch (Throwable).
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
