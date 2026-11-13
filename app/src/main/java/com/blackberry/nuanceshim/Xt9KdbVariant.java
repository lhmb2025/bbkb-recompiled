package com.blackberry.nuanceshim;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import dev.bbkb.ime.core.layout.KdbStore;
import dev.bbkb.ime.core.layout.KeypadKdbs;
import dev.bbkb.ime.core.shared.SystemProps;

import java.util.List;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * KDB variant registry of the owned ET9KDB module, keyed by (variant, primaryId, secondaryId).
 * {@link #apply} builds the device's keypad KDBs from keypad_pkb_&lt;variant&gt;.xml (or the generic
 * keypad_pkb.xml); {@link #registerGenerated}/{@link #selectVariant} carry letter-layout KDBs.
 * The owned SetKdbNum picks from the selected variant and falls back to the root assets/kdb/ files.
 * Only effective in a CUTOVER build (libkb.so); DIFF and stock builds fall back to the root files.
 */
public final class Xt9KdbVariant {
    private static final String TAG = "XT9KDB";
    private static boolean sLoaded;
    private static volatile String sApplied;   // selected variant ("" = default), to skip rework
    // The device's variant as apply settled it; differs from sApplied while a generated variant is selected.
    private static volatile String sDeviceVariant;
    // Runs on the registration thread when the device variant changes, so generated variants rebuild.
    private static volatile Runnable sDeviceVariantListener;

    /** The longest variant name the owned module accepts, in bytes (XT9KDB_VARIANT_NAME_MAX - 1). */
    public static final int MAX_VARIANT_NAME_BYTES = 79;

    /** The native registry calls; a seam so JVM tests can drive registration without the library. */
    @VisibleForTesting
    public interface NativeBridge {
        /** 0 when registered. */
        int register(String variant, byte[] xml);

        void setVariant(String variant);
    }

    private static final NativeBridge LIBRARY = new NativeBridge() {
        @Override
        public int register(String variant, byte[] xml) {
            return nativeRegister(variant, xml);
        }

        @Override
        public void setVariant(String variant) {
            nativeSetVariant(variant);
        }
    };

    private static volatile NativeBridge sNative = LIBRARY;
    private static volatile boolean sTestBridge;

    /** Selected variant ("" = the root files only, null = none applied yet). Debug/logging use. */
    public static String applied() {
        return sApplied;
    }

    /**
     * The variant the device's keypad KDBs are registered under ({@link KeypadKdbs#GENERIC} when
     * none is named or its file is missing), null before the first apply.
     */
    public static String deviceVariant() {
        return sDeviceVariant;
    }

    /** Whether there is an owned KDB registry to talk to (a CUTOVER or DIFF build). */
    public static boolean isAvailable() {
        return sLoaded || sTestBridge;
    }
    // One FIFO executor: awaiting the latest Future also awaits every earlier job.
    private static final ExecutorService APPLY_EXEC = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Xt9KdbVariantApply");
        t.setDaemon(true);
        return t;
    });
    // Latest registration job; loaders await it so the engine never selects a KDB before registration.
    private static volatile Future<?> sPending;
    // Held while enqueueing so sPending is always the job enqueued last.
    private static final Object SUBMIT_LOCK = new Object();

    static {
        // CUTOVER builds ship libkb.so, DIFF builds libxt9kdb.so; stock builds neither (feature inert).
        try { System.loadLibrary("kb"); sLoaded = true; } catch (Throwable ignored) { /* try libxt9kdb next */ }
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
     * Builds, registers and selects the device's keypad KDBs (null/empty = generic). No-op when
     * unchanged; a change invalidates the active KDB natively. Runs on a worker thread so cold
     * start does not block; KDB load paths call {@link #awaitReady} before the first SetKdbNum.
     */
    public static void apply(Context context, String variant) {
        if (!isAvailable() || context == null) return;
        String requested = (variant == null) ? "" : variant.trim();
        // Debug: debug.et9.kdbvariant overrides the variant ("default" = generic); force-stop the IME after setting it.
        if (dev.bbkb.ime.BuildConfig.DEBUG) {
            String o = readDebugVariantOverride();
            if (o != null) {
                requested = o.equals("default") ? "" : o;
                Log.w(TAG, "variant: debug.et9.kdbvariant override active -> '" + requested + "'");
            }
        }
        final String v = requested.isEmpty() ? KeypadKdbs.GENERIC : requested;
        // Advisory fast path; applySync re-checks under the lock. Compares the device variant, not
        // the selected one, so a selected generated variant does not re-register the device files.
        if (v.equals(sDeviceVariant)) return;

        final Context app = context.getApplicationContext();
        enqueue(() -> {
            if (applySync(app, v)) notifyDeviceVariantChanged();
        });
    }

    /**
     * Registers an in-memory KDB under {@code variant} (e.g. {@code layout:<id>}), keyed by the
     * file's primaryId/secondaryId; re-registering replaces the bytes. Call via {@link #submit} so
     * layout syncs wait for it.
     *
     * @return false when there is no owned registry, the name is empty or too long
     *     ({@link #MAX_VARIANT_NAME_BYTES}), or the native side refused the bytes
     */
    public static boolean registerGenerated(String variant, byte[] xml) {
        if (!isAvailable() || variant == null || variant.isEmpty() || xml == null || xml.length == 0) {
            return false;
        }
        if (variant.getBytes(StandardCharsets.UTF_8).length > MAX_VARIANT_NAME_BYTES) {
            Log.w(TAG, "variant: name too long to register: " + variant);
            return false;
        }
        try {
            return sNative.register(variant, xml) == 0;
        } catch (Throwable t) {
            Log.w(TAG, "variant: register failed for '" + variant + "': " + t.getMessage());
            return false;
        }
    }

    /**
     * Selects an already registered variant for the next PKB layout load ({@code ""}/null = root
     * layouts); a change invalidates the loaded KDB. Registers nothing; missing ids use the root copy.
     */
    public static synchronized void selectVariant(String variant) {
        if (!isAvailable()) return;
        final String v = (variant == null) ? "" : variant;
        if (v.equals(sApplied)) return;
        try {
            sNative.setVariant(v);
            sApplied = v;
            Log.i(TAG, "variant selected: '" + (v.isEmpty() ? "(default)" : v) + "'"
                    + (v.equals(sDeviceVariant) ? " (device)" : ""));
        } catch (Throwable t) {
            Log.w(TAG, "variant: nativeSetVariant failed: " + t.getMessage());
        }
    }

    /**
     * Runs {@code job} on the registration thread after every earlier job; {@link #awaitReady}
     * makes layout syncs wait for it. No-op without an owned registry.
     */
    public static void submit(Runnable job) {
        if (!isAvailable() || job == null) return;
        enqueue(job);
    }

    /** Runs on the registration thread when {@link #apply} changes the device variant (null to stop). */
    public static void setDeviceVariantListener(Runnable listener) {
        sDeviceVariantListener = listener;
    }

    private static void enqueue(Runnable job) {
        synchronized (SUBMIT_LOCK) {
            sPending = APPLY_EXEC.submit(job);
        }
    }

    private static void notifyDeviceVariantChanged() {
        final Runnable listener = sDeviceVariantListener;
        if (listener == null) return;
        try {
            listener.run();
        } catch (Throwable t) {
            Log.w(TAG, "variant: device variant listener failed", t);
        }
    }

    /** JVM tests: replaces the native calls (null restores them) and forgets all variant state. */
    @VisibleForTesting
    public static void setNativeBridgeForTest(NativeBridge bridge) {
        awaitReady(5000);
        synchronized (Xt9KdbVariant.class) {
            sNative = (bridge != null) ? bridge : LIBRARY;
            sTestBridge = bridge != null;
            sApplied = null;
            sDeviceVariant = null;
            sDeviceVariantListener = null;
            sPending = null;
        }
    }

    /**
     * Ranks candidates against libkb's stored gesture path; the winning index, or -1. Only valid
     * right after a gesture's buildSelectionList, while the stored path is still that gesture's.
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

    /** Monotonic deposit sequence of the current gesture snapshot (consume-once key); -1 without the lib. */
    public static int rankSeq() {
        if (!sLoaded) return -1;
        try {
            return nativeRankSeq();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static native int nativeRankSeq();

    /** zipf*10 of a lowercase English word in kdb_freq_en.h; 0 when absent or without the lib. */
    public static int wordZipf10(String lowercaseWord) {
        if (!sLoaded || lowercaseWord == null || lowercaseWord.isEmpty()) return 0;
        try {
            return nativeWordZipf10(lowercaseWord);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static native int nativeWordZipf10(String word);

    private static native boolean nativePurgeHeap();

    /**
     * Returns the allocator's cached free pages to the kernel (mallopt M_PURGE, Android 9+; false
     * elsewhere). Called after the keyboard hides and under memory pressure.
     */
    public static boolean purgeHeap() {
        if (!sLoaded) return false;
        try {
            return nativePurgeHeap();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Times this process the owned module could not reach a gesture recognizer (null handle, or
     * {@code ET9KDB_ProcessTrace} failed to bind); non-zero means swipe is dead. Needed because
     * {@code ET9KDB_TouchEnd} must always report success (the blob maps non-zero to false).
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
     * Times a recognizer returned a non-success status that {@code TouchEnd} swallowed; non-fatal,
     * the gesture produced nothing.
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

    /** The gesture word ranked in C at deposit time for {@code seq} (consume-once), used verbatim;
     * null if none (debug.et9.ownrankc off, ranker declined, or already taken). */
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

    /** Blocks (bounded) until pending registration jobs finish; called by the KDB load paths. */
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

    /** @return whether the device variant changed (and was selected). */
    private static synchronized boolean applySync(Context context, String variant) {
        String v = variant;
        if (v.equals(sDeviceVariant)) return false;

        List<byte[]> files = null;
        try {
            files = deviceFiles(context, v);
            if (files == null && !v.equals(KeypadKdbs.GENERIC)) {
                Log.w(TAG, "variant '" + v + "': no kdb/" + KeypadKdbs.templateFile(v)
                        + " — using the generic keypad");
                v = KeypadKdbs.GENERIC;
                if (v.equals(sDeviceVariant)) return false;
                files = deviceFiles(context, v);
            }
        } catch (Throwable t) {
            Log.w(TAG, "variant '" + v + "': building the keypad KDBs failed: " + t.getMessage());
        }
        int registered = 0;
        if (files != null) {
            for (byte[] xml : files) {
                if (sNative.register(v, xml) == 0) registered++;
                else Log.w(TAG, "variant '" + v + "': the engine refused a keypad KDB");
            }
        }
        if (registered == 0) {
            Log.w(TAG, "variant '" + v + "': no keypad KDBs registered — the engine uses the shipped files");
        }

        try {
            sNative.setVariant(v);
            sApplied = v;
            sDeviceVariant = v;
            Log.i(TAG, "variant applied: '" + v + "' (" + registered + " keypad KDB(s) of "
                    + KeypadKdbs.templateFile(v) + ")");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "variant: nativeSetVariant failed: " + t.getMessage());
            return false;
        }
    }

    /** The device's keypad KDBs, cached in {@link KdbStore}; null when it has no geometry file. */
    private static List<byte[]> deviceFiles(Context context, String variant) {
        final KdbStore store = KdbStore.forApp(context);
        final String key = KdbStore.key("device", variant);
        final List<byte[]> stored = store.get(key);
        if (stored != null) return stored;
        final List<byte[]> files = KeypadKdbs.forDevice(context, variant);
        if (files != null) store.put(key, files);
        return files;
    }

    /** Package-visible for Xt9Kdb.selfCheck. */
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
