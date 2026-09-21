package com.blackberry.nuanceshim;

import android.util.Log;

import dev.bbkb.ime.core.shared.SystemProps;
import dev.bbkb.ime.BuildConfig;

/**
 * Debug-only access to the stock ET9 engine's configuration knobs.
 *
 * <p>The blob binds only two of its 49 settings to Java (see
 * {@code docs/et9-ranking-documentation.md}). This class reaches the rest through
 * {@code libxt9trace.so}, which dlsyms the exported {@code ET9AW*} entry points at runtime.
 * Every call validates the engine's ABI fingerprint before touching memory and returns a
 * diagnostic instead of crashing if the layout does not match.
 *
 * <p>Guarded by {@link BuildConfig#DEBUG} at every entry: this is an investigation tool, not
 * a product surface. When a knob graduates to a real setting it should get a proper binding
 * rather than going through here.
 *
 * <p>View output: {@code adb logcat -s ET9PROBE:I Et9Probe:I}
 */
public final class Et9Probe {

    private static final String TAG = "Et9Probe";

    static {
        try { System.loadLibrary("xt9trace"); }
        catch (Throwable t) { Log.e(TAG, "libxt9trace load failed", t); }
    }

    private Et9Probe() {}

    private static native String nativeProbe(long handle);
    private static native int nativeSetFences(long handle, int primary, int secondary);
    private static native int nativeSetQuarantine(long handle, int level);

    /** System property that opts in to {@link #probe}: {@code adb shell setprop debug.et9.probe 1}. */
    static final String PROBE_PROPERTY = "debug.et9.probe";

    /**
     * Whether {@link #probe} may run. Off unless {@value #PROBE_PROPERTY} is {@code 1}.
     *
     * <p>Opt-in because the probe reads raw engine memory, and on a cold IME start (the process
     * killed, then restarted straight into {@code onStartInput}) it SIGSEGVs inside
     * {@code nativeProbe} while dereferencing the settings block — three times in about eight
     * cold starts on the KEY2 (2026-09-15), always within ~5 s of process start, never on a warm
     * session. The likely cause is the context passing the fingerprint checks before the engine
     * has finished building it; that is not confirmed. A native crash cannot be caught, so running
     * the probe on every debug session made the debug build itself unstable.
     *
     * <p>The {@code setprop debug.et9.fence N} experiment hook lives inside {@code nativeProbe}
     * ({@code et9probe.c}), so it also needs this property set.
     */
    static boolean isProbeEnabled() {
        return "1".equals(SystemProps.get(PROBE_PROPERTY));
    }

    /**
     * Read the engine's current ranking/adaptation configuration.
     *
     * @param sdk the live engine instance (from {@code NuanceSDKManager.getInstance()})
     * @return a JSON-ish diagnostic string, or null if unavailable or not opted in
     */
    public static String probe(NuanceSDK sdk) {
        if (!BuildConfig.DEBUG || sdk == null || !isProbeEnabled()) return null;
        try {
            String result = nativeProbe(sdk.getNativeHandleForDebug());
            Log.i(TAG, "probe: " + result);
            return result;
        } catch (Throwable t) {
            Log.w(TAG, "probe failed", t);
            return null;
        }
    }

    /** Set both auto-correct fences. Returns the engine status (0 = success). */
    public static int setFences(NuanceSDK sdk, int primary, int secondary) {
        if (!BuildConfig.DEBUG || sdk == null) return -1;
        try {
            return nativeSetFences(sdk.getNativeHandleForDebug(), primary, secondary);
        } catch (Throwable t) {
            Log.w(TAG, "setFences failed", t);
            return -1;
        }
    }

    /** Set the DLM quarantine level. Returns the engine status (0 = success). */
    public static int setQuarantine(NuanceSDK sdk, int level) {
        if (!BuildConfig.DEBUG || sdk == null) return -1;
        try {
            return nativeSetQuarantine(sdk.getNativeHandleForDebug(), level);
        } catch (Throwable t) {
            Log.w(TAG, "setQuarantine failed", t);
            return -1;
        }
    }
}
