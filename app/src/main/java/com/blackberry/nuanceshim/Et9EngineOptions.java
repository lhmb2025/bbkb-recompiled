package com.blackberry.nuanceshim;

import android.util.Log;

import dev.bbkb.ime.BuildConfig;

/**
 * Engine options the blob implements but its JNI never binds, reached through
 * {@code libxt9trace.so} ({@code et9probe.c}). Package-private: {@link NuanceSDK} is the only
 * caller, under its engine mutex.
 *
 * <p>Results below zero mean this side could not reach the engine; zero and above are the
 * engine's own status codes (0 = done).
 */
final class Et9EngineOptions {

    private static final String TAG = "Et9EngineOptions";

    /** The engine context behind the handle could not be resolved. */
    static final int ERR_CONTEXT = -1;
    /** The engine library is not loaded, or is not the build the shim was pinned to. */
    static final int ERR_BLOB = -2;

    private static boolean sLoaded;

    static {
        try {
            System.loadLibrary("xt9trace");
            sLoaded = true;
        } catch (Throwable t) {
            Log.e(TAG, "libxt9trace load failed", t);
        }
    }

    private Et9EngineOptions() {}

    static boolean isLoaded() {
        return sLoaded;
    }

    /**
     * Turns the engine's multithreaded selection-list and spell search off (or back on). With it
     * on, the engine starts and joins up to five short-lived threads per keystroke; with it off
     * the same search runs inline on the calling thread. Native {@code setLanguage} resets the
     * engine to multithreaded, so this has to follow every language activation.
     *
     * @return the engine's status (0 = done, 62 = busy), or {@link #ERR_CONTEXT} /
     *     {@link #ERR_BLOB}
     */
    static int setMultiThreading(long handle, boolean enabled) {
        if (handle == 0 || !sLoaded) return ERR_CONTEXT;
        int status = nativeSetMultiThreading(handle, enabled);
        if (status != 0 && BuildConfig.DEBUG) {
            Log.w(TAG, "setMultiThreading(" + enabled + ") -> " + status);
        }
        return status;
    }

    private static native int nativeSetMultiThreading(long handle, boolean enabled);
}
