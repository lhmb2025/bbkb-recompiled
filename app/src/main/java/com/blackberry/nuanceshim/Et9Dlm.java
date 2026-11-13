package com.blackberry.nuanceshim;

import android.util.Log;

/**
 * Learned-model export and import via {@code libxt9trace.so} ({@code et9dlm.c}): the blob exports
 * {@code ET9AWDLMExport}/{@code ET9AWDLMImport} but binds neither to Java. Call only through
 * {@link NuanceSDK#exportDLM()} / {@link NuanceSDK#importDLM(byte[])}, which hold the engine mutex.
 *
 * <p>Results below zero mean this side could not reach the engine; zero and above are the
 * engine's own status codes.
 */
final class Et9Dlm {

    private static final String TAG = "Et9Dlm";

    /** The engine context behind the handle could not be resolved. */
    static final int ERR_CONTEXT = -1;
    /** The engine library is not loaded, or is not the build the shim was pinned to. */
    static final int ERR_BLOB = -2;
    /** The engine reports nothing to export. */
    static final int ERR_EMPTY = -3;
    static final int ERR_ARGS = -4;
    static final int ERR_MEMORY = -5;

    private static boolean sLoaded;

    static {
        try {
            System.loadLibrary("xt9trace");
            sLoaded = true;
        } catch (Throwable t) {
            Log.e(TAG, "libxt9trace load failed", t);
        }
    }

    private Et9Dlm() {}

    static boolean isLoaded() {
        return sLoaded;
    }

    /** The engine's export buffer size, or a negative ERR_* code. */
    static native int nativeExportMaxSize(long handle);

    /** The model as the engine's export stream, checksum included, or null. */
    static native byte[] nativeExport(long handle);

    /** Replaces the model; the engine's status, or a negative ERR_* code. */
    static native int nativeImport(long handle, byte[] data);

    /**
     * Switches the learned model off or back on for this engine instance only; the engine's
     * status (0 = done), or a negative ERR_* code.
     */
    static native int nativeSetLearnedModelEnabled(long handle, boolean enabled);

    /**
     * The source of each of the first {@code count} words of the built selection list
     * ({@link NuanceSDK#WORD_SOURCE_LEARNED_MODEL} and the others), or null.
     */
    static native byte[] nativeSelectionListSources(long handle, int count);
}
