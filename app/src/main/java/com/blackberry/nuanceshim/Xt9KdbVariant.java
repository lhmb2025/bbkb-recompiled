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
 * Device-variant KDB registration for the OWNED ET9KDB module.
 *
 * Every device has one physical-keypad geometry, assets/kdb/keypad_pkb_&lt;variant&gt;.xml for the
 * variant its config names (<kdb-variant> in device_config_*.xml), assets/kdb/keypad_pkb.xml
 * ({@link KeypadKdbs#GENERIC}) for the rest. {@link #apply} builds the engine's keypad KDBs from
 * it ({@link KeypadKdbs}: one per engine language id, each with the letters its shipped file used
 * to carry) and registers them under the variant in the owned module's registry, keyed by
 * (primaryId, secondaryId); the owned ET9KDB_SetKdbNum picks from the selected variant first and
 * falls back to the root assets/kdb/ files, which the blob indexes itself, for any id the variant
 * lacks (the keyboards whose letters no letter layout holds yet keep shipped files).
 *
 * The same registry carries the KDBs generated for a letter layout
 * ({@code dev.bbkb.ime.core.layout.LetterLayoutEngineSync}): {@link #registerGenerated} hands
 * over bytes built in memory rather than read from an asset folder, and {@link #selectVariant}
 * makes them the engine's while the layout drives the keys. The device variant stays what
 * {@link #apply} settled on ({@link #deviceVariant}); a generated variant is built from it and
 * gives way to it again when the layout stops applying.
 *
 * Only effective in a CUTOVER build (libkb.so owns SetKdbNum). In a DIFF build the registry is
 * populated but the blob still selects layouts, and in a stock build no owned lib loads — both
 * degrade gracefully to the root layouts.
 */
public final class Xt9KdbVariant {
    private static final String TAG = "XT9KDB";
    private static boolean sLoaded;
    private static volatile String sApplied;   // selected variant ("" = default), to skip rework
    // The variant the device profile asked for, as apply settled it ("" = root layouts, null =
    // no apply yet). Kept apart from sApplied because a letter layout's generated variant can be
    // the selected one while this stays the device's.
    private static volatile String sDeviceVariant;
    // Told, on the registration thread, when apply changes the device variant: a generated
    // variant was built from the old one's files and has to be rebuilt.
    private static volatile Runnable sDeviceVariantListener;

    /** The longest variant name the owned module accepts, in bytes (XT9KDB_VARIANT_NAME_MAX - 1). */
    public static final int MAX_VARIANT_NAME_BYTES = 79;

    /**
     * The native registry calls. A seam only so JVM tests can drive the registration logic: the
     * owned library cannot load there, and every method here is a no-op without it.
     */
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
     * The device profile's variant as {@link #apply} settled it: the name its keypad KDBs are
     * registered under ({@link KeypadKdbs#GENERIC} for a device whose config names none, or whose
     * named geometry file is missing), null before the first apply.
     */
    public static String deviceVariant() {
        return sDeviceVariant;
    }

    /** Whether there is an owned KDB registry to talk to (a CUTOVER or DIFF build). */
    public static boolean isAvailable() {
        return sLoaded || sTestBridge;
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
    // Most recent registration job (an apply or a submitted one); loaders await this so the
    // engine never selects a KDB before the variant registry is populated.
    private static volatile Future<?> sPending;
    // Held while enqueueing, so sPending is always the job enqueued last; FIFO then makes
    // awaiting it await every earlier job as well, whichever thread submitted them.
    private static final Object SUBMIT_LOCK = new Object();

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
     * Build the device's keypad KDBs from its geometry file ({@link KeypadKdbs#forDevice}),
     * register them with the owned KDB module and select the variant. Null/empty means the
     * generic keypad. Safe to call repeatedly (no-op when the variant is unchanged); a real change
     * invalidates the active KDB natively, so the next keyboard layout sync reloads through the
     * new selection.
     *
     * The build runs on a worker thread so
     * DeviceProfile.initialize doesn't block cold start on them; KDB load paths call
     * {@link #awaitReady} first, which guarantees the registration completes before the engine's
     * first SetKdbNum.
     */
    public static void apply(Context context, String variant) {
        if (!isAvailable() || context == null) return;
        String requested = (variant == null) ? "" : variant.trim();
        // debug.et9.kdbvariant overrides the device-config variant for on-device geometry A/B
        // ("athena_test" loads keypad_pkb_athena_test.xml, "default" the generic keypad). Debug
        // builds only; set the prop, then force-stop the IME so the next apply sees it.
        if (dev.bbkb.ime.BuildConfig.DEBUG) {
            String o = readDebugVariantOverride();
            if (o != null) {
                requested = o.equals("default") ? "" : o;
                Log.w(TAG, "variant: debug.et9.kdbvariant override active -> '" + requested + "'");
            }
        }
        final String v = requested.isEmpty() ? KeypadKdbs.GENERIC : requested;
        // Advisory fast path only -- sDeviceVariant is volatile so the read cannot tear, but two
        // concurrent applies of the same variant can both pass it. applySync re-checks under
        // the class monitor, which is the authoritative test. It compares against the device
        // variant, not the selected one: a letter layout's generated variant being selected is
        // no reason to register the device's files again.
        if (v.equals(sDeviceVariant)) return;

        final Context app = context.getApplicationContext();
        enqueue(() -> {
            if (applySync(app, v)) notifyDeviceVariantChanged();
        });
    }

    /**
     * Registers one KDB built in memory under {@code variant} (a letter layout's
     * {@code layout:<id>}), keyed natively by the file's own primaryId/secondaryId like the
     * device variant's files. Registering the same (variant, primaryId, secondaryId) again
     * replaces the bytes, and the engine reloads them on its next layout sync if they were the
     * loaded ones. Call it on the registration thread ({@link #submit}) so layout syncs wait
     * for it.
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
     * Makes {@code variant} the one the engine's next PKB layout load picks from ({@code ""} or
     * null for the root layouts); a real change invalidates the loaded KDB natively, exactly as
     * a device variant change does. Unlike {@link #apply} it registers nothing: the variant's
     * files must be registered already ({@link #registerGenerated}, or apply's own folder scan).
     * A layout the variant does not carry falls back to the root copy.
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
     * Runs {@code job} on the registration thread, after every apply and job enqueued before
     * it. KDB layout syncs wait for it through {@link #awaitReady}, so whatever the job
     * registers and selects is in place before the engine's next SetKdbNum. A no-op without an
     * owned registry.
     */
    public static void submit(Runnable job) {
        if (!isAvailable() || job == null) return;
        enqueue(job);
    }

    /**
     * Called on the registration thread whenever {@link #apply} changes the device variant
     * (null to stop). The letter-layout sync uses it to rebuild a generated variant from the
     * new device files.
     */
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

    /**
     * Replaces the native registry calls (null restores the library's) and forgets every
     * variant applied or selected so far. JVM tests only: with a bridge in place the class
     * behaves as if the owned library had loaded.
     */
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
     * zipf*10 of a lowercase English word in the LDB frequency table the owned decoder ships
     * (kdb_freq_en.h), or 0 when the word is absent from it or the native library is unavailable.
     * The typed-word guard weighs the two spellings of an apostrophe correction with it.
     */
    public static int wordZipf10(String lowercaseWord) {
        if (!sLoaded || lowercaseWord == null || lowercaseWord.isEmpty()) return 0;
        try {
            return nativeWordZipf10(lowercaseWord);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static native int nativeWordZipf10(String word);

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

    /**
     * The device's keypad KDBs ({@link KeypadKdbs#forDevice}), read back from the app's
     * {@link KdbStore} when built before (once per install); null when the device has no geometry
     * file of its own.
     */
    private static List<byte[]> deviceFiles(Context context, String variant) {
        final KdbStore store = KdbStore.forApp(context);
        final String key = KdbStore.key("device", variant);
        final List<byte[]> stored = store.get(key);
        if (stored != null) return stored;
        final List<byte[]> files = KeypadKdbs.forDevice(context, variant);
        if (files != null) store.put(key, files);
        return files;
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
