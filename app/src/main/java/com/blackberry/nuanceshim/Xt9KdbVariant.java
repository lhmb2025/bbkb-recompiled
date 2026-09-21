package com.blackberry.nuanceshim;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import dev.bbkb.ime.core.shared.SystemProps;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Device-variant KDB registration for the OWNED ET9KDB module.
 *
 * A device config can name a KDB variant (<kdb-variant> in device_config_*.xml); the layouts in
 * assets/kdb/&lt;variant&gt;/ then override the same-named root assets/kdb/ layouts per
 * (primaryId, secondaryId), with root fallback for anything the folder doesn't carry. The blob's
 * own asset indexer scans assets/kdb/ flat (never subfolders), so this class reads the variant
 * files and hands the bytes to the owned module's registry; the owned ET9KDB_SetKdbNum does the
 * per-layout selection.
 *
 * Only effective in a CUTOVER build (libkb.so owns SetKdbNum). In a DIFF build the registry is
 * populated but the blob still selects layouts, and in a stock build no owned lib loads — both
 * degrade gracefully to the root layouts.
 */
public final class Xt9KdbVariant {
    private static final String TAG = "XT9KDB";
    private static boolean sLoaded;
    private static volatile String sApplied;   // last applied variant ("" = default), to skip rework

    /** Last applied variant ("" = default layouts, null = none applied yet). Debug/logging use. */
    public static String applied() {
        return sApplied;
    }
    // Applies run on one shared single-thread executor rather than a fresh bare Thread per
    // call: it is FIFO, so awaiting the most recently submitted Future also awaits every
    // earlier one, and a Future carries its own completion state. The old CountDownLatch was
    // published before the thread started and never cleared, so awaitReady kept re-awaiting a
    // spent latch -- and a second apply() with a different variant overwrote the field, which
    // could release a waiter as soon as the *wrong* apply finished.
    private static final ExecutorService APPLY_EXEC = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Xt9KdbVariantApply");
        t.setDaemon(true);
        return t;
    });
    // Most recent async apply; loaders await this so the engine never selects a KDB
    // before the variant registry is populated.
    private static volatile Future<?> sPending;

    static {
        // CUTOVER builds ship the owned module as libkb.so, DIFF builds as libxt9kdb.so;
        // exactly one exists in any given APK. Stock builds have neither -> feature inert.
        try { System.loadLibrary("kb"); sLoaded = true; } catch (Throwable ignored) { }
        if (!sLoaded) {
            try { System.loadLibrary("xt9kdb"); sLoaded = true; } catch (Throwable t) {
                Log.i(TAG, "variant: no owned KDB lib — device KDB variants inactive");
            }
        }
    }

    private Xt9KdbVariant() {}

    private static native int  nativeRegister(String variant, byte[] xml);
    private static native void nativeSetVariant(String variant);

    /**
     * Register assets/kdb/&lt;variant&gt;/*.xml with the owned KDB module and select the variant.
     * Pass null/empty to select the default (root) layouts. Safe to call repeatedly (no-op when
     * the variant is unchanged); a real change invalidates the active KDB natively, so the next
     * keyboard layout sync reloads through the new selection.
     *
     * The asset reads run on a worker thread (~58 ms for 19 layouts, measured on the KEY2) so
     * DeviceProfile.initialize doesn't block cold start on them; KDB load paths call
     * {@link #awaitReady} first, which guarantees the registration completes before the engine's
     * first SetKdbNum.
     */
    public static void apply(Context context, String variant) {
        if (!sLoaded || context == null) return;
        String requested = (variant == null) ? "" : variant.trim();
        // debug.et9.kdbvariant overrides the device-config variant for on-device geometry A/B
        // ("default" forces the root/original flat layouts). Debug builds only; set the prop,
        // then force-stop the IME so the next apply sees it.
        if (dev.bbkb.ime.BuildConfig.DEBUG) {
            String o = readDebugVariantOverride();
            if (o != null) {
                requested = o.equals("default") ? "" : o;
                Log.w(TAG, "variant: debug.et9.kdbvariant override active -> '" + requested + "'");
            }
        }
        final String v = requested;
        // Advisory fast path only -- sApplied is volatile so the read cannot tear, but two
        // concurrent applies of the same variant can both pass it. applySync re-checks under
        // the class monitor, which is the authoritative test.
        if (v.equals(sApplied)) return;

        final Context app = context.getApplicationContext();
        sPending = APPLY_EXEC.submit(() -> applySync(app, v));
    }

    /**
     * Owned gesture ranker (remediation log §8.7.6): ranks candidate words against the current
     * stored gesture path in libkb (per-row-normalized ideal-path scoring). Returns the winning
     * index into words, or -1 if unrankable. Only meaningful right after a gesture's
     * buildSelectionList, while the stored path is still that gesture's.
     */
    public static int rankGesture(String[] words) {
        if (!sLoaded || words == null || words.length == 0) return -1;
        try {
            return nativeRankGesture(words);
        } catch (Throwable t) {
            Log.w(TAG, "rankGesture failed", t);
            return -1;
        }
    }

    private static native int nativeRankGesture(String[] words);

    /** Deposit sequence of the current gesture snapshot (§8.7.11 consume-once key); -1 if the
     * native lib is unavailable. Monotonic per deposit; the bridge ranks each sequence once. */
    public static int rankSeq() {
        if (!sLoaded) return -1;
        try {
            return nativeRankSeq();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static native int nativeRankSeq();

    /**
     * AUDIT L10: how many times the owned module could not reach a gesture recognizer at all
     * this process — a null engine handle, or the blob's {@code ET9KDB_ProcessTrace} failing to
     * bind. Monotonic, never reset; {@code 0} means the path is healthy.
     *
     * <p>This exists because {@code ET9KDB_TouchEnd} <em>must</em> report success whatever
     * happened: the blob's JNI shim maps any non-zero {@code ET9STATUS} to {@code false} and the
     * blob's own dispatcher hardcodes 0, so the owned module deliberately never propagates the
     * recognizer's status. In September 2026 that let a KEY2 build run with swipe completely dead
     * for the life of every process — libkb.so loaded before the blob, the weak reference
     * resolved to NULL — with nothing but a logcat line to say so. A non-zero value here means
     * swipe is <em>dead</em>, not merely inaccurate.
     *
     * @return the count, or -1 when the native library is unavailable (a stock or DIFF build).
     */
    public static int recognizerUnavailable() {
        if (!sLoaded) return -1;
        try {
            return nativeRecognizerUnavailable();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static native int nativeRecognizerUnavailable();

    /**
     * AUDIT L10: how many times a recognizer ran this process and returned a non-success status
     * that {@code TouchEnd} then swallowed. Unlike {@link #recognizerUnavailable()} a non-zero
     * value is not fatal — the gesture simply produced nothing.
     *
     * @return the count, or -1 when the native library is unavailable.
     */
    public static int recognizerFailures() {
        if (!sLoaded) return -1;
        try {
            return nativeRecognizerFailures();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static native int nativeRecognizerFailures();

    /** W1 (§8.7.14): the deposit-time-ranked gesture word for {@code seq} (consume-once); null if
     * none (debug.et9.ownrankc off, ranker declined, or already taken). When non-null the bridge
     * uses it verbatim — the selection list was built and ranked in C at the deposit, so the
     * request-queue contamination that plagues the Java-side build (§8.7.13) cannot occur. */
    public static String takeGestureWord(int seq) {
        if (!sLoaded) return null;
        try {
            return nativeTakeGestureWord(seq);
        } catch (Throwable t) {
            return null;
        }
    }

    private static native String nativeTakeGestureWord(int seq);

    /** Reads debug.et9.kdbvariant via the SystemProperties hidden API; null when unset. */
    private static String readDebugVariantOverride() {
        final String val = SystemProps.get("debug.et9.kdbvariant");
        return (val == null || val.trim().isEmpty()) ? null : val.trim();
    }

    /**
     * Block (bounded) until any pending {@link #apply} finishes. Called by the KDB load paths;
     * a no-op when nothing is pending, and registration normally finishes long before the first
     * layout load needs it.
     */
    public static void awaitReady(long timeoutMs) {
        final Future<?> pending = sPending;
        if (pending == null || pending.isDone()) return;
        try {
            pending.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            Log.w(TAG, "variant: apply still pending after " + timeoutMs + " ms — loading with current registry");
        } catch (ExecutionException e) {
            Log.w(TAG, "variant: apply failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static synchronized void applySync(Context context, String variant) {
        String v = variant;
        if (v.equals(sApplied)) return;

        int registered = 0;
        if (!v.isEmpty()) {
            final String dir = "kdb/" + v;
            try {
                AssetManager am = context.getAssets();
                String[] files = am.list(dir);
                if (files != null) {
                    for (String f : files) {
                        if (!f.endsWith(".xml")) continue;
                        byte[] xml = readAsset(am, dir + "/" + f);
                        if (xml != null && nativeRegister(v, xml) == 0) registered++;
                        else Log.w(TAG, "variant: failed to register " + dir + "/" + f);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "variant: asset scan failed for " + dir + ": " + t.getMessage());
            }
            if (registered == 0) {
                Log.w(TAG, "variant '" + v + "': no layouts registered — using default KDBs");
                v = "";
            }
        }

        try {
            nativeSetVariant(v);
            sApplied = v;
            Log.i(TAG, "variant applied: '" + (v.isEmpty() ? "(default)" : v)
                    + "' (" + registered + " layout(s) registered)");
        } catch (Throwable t) {
            Log.w(TAG, "variant: nativeSetVariant failed: " + t.getMessage());
        }
    }

    /** Package-visible so Xt9Kdb.selfCheck can share this loop rather than keeping its own
     *  copy, which lacked try-with-resources and leaked the asset fd on a read failure. */
    static byte[] readAsset(AssetManager am, String path) {
        try (InputStream is = am.open(path)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int r;
            while ((r = is.read(tmp)) > 0) bos.write(tmp, 0, r);
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "variant: cannot read asset " + path + ": " + t.getMessage());
            return null;
        }
    }
}
