package dev.bbkb.ime.core.engine;

import android.util.Log;

import com.blackberry.nuanceshim.NuanceSDK;

import java.util.concurrent.Future;

import dev.bbkb.ime.BuildConfig;

/**
 * The engine-thread entry points callers use once they stop talking to {@link NuanceSDK}
 * directly (Wave 3 Phase A). Every method resolves the engine INSIDE the operation, on the thread
 * that runs it, so the main thread never joins an engine start and never takes the engine lock:
 *
 * <ul>
 *   <li>{@link #post}: fire-and-forget on the engine thread, in FIFO order with every other post
 *       and with the suggestion requests. A feed posted before a request is in the engine's buffer
 *       when the request runs; that order is the whole correctness model, so never add a delay.</li>
 *   <li>{@link #query}: run on the engine thread and wait, bounded. For the few results the caller
 *       needs now (a converter's composed text, a validity check that decides a revert). In steady
 *       state the engine thread is idle and the wait is one context switch; behind a language load
 *       it waits as the direct call used to, up to the timeout, then the fallback is returned.</li>
 *   <li>{@link #load}: run on the loader thread, which owns the seconds-long holds (language
 *       loads, contact imports, learned-model transfer and enumeration, dispose). Returns a future
 *       that only a non-main thread may wait on.</li>
 * </ul>
 *
 * The {@code Secondary} variants address the spell checker's engine ({@link
 * NuanceSDKManager#getSecondary()}); {@code peekSecondary} variants do nothing when it is not
 * loaded, for callers that must not create it (pack install, model re-attach).
 */
public final class EngineOps {

    private static final String TAG = "EngineOps";

    public interface SdkOp {
        void run(NuanceSDK sdk);
    }

    public interface SdkQuery<T> {
        T run(NuanceSDK sdk);
    }

    private EngineOps() {}

    /** Posts {@code op} to the engine thread against the primary engine; dropped (debug log) when there is none. */
    public static void post(String site, SdkOp op) {
        EngineThreads.post(() -> {
            final NuanceSDK sdk = NuanceSDKManager.sdkOrWarn(site);
            if (sdk != null) op.run(sdk);
        });
    }

    /** Runs {@code op} on the engine thread against the primary engine and waits up to {@code timeoutMs}. */
    public static <T> T query(String site, long timeoutMs, T fallback, SdkQuery<T> op) {
        return EngineThreads.query(() -> {
            final NuanceSDK sdk = NuanceSDKManager.sdkOrWarn(site);
            return sdk == null ? fallback : op.run(sdk);
        }, timeoutMs, fallback);
    }

    /** Runs {@code op} on the loader thread against the primary engine (created or joined there). */
    public static <T> Future<T> load(String site, SdkQuery<T> op) {
        return EngineThreads.load(() -> {
            final NuanceSDK sdk = NuanceSDKManager.sdkOrWarn(site);
            return sdk == null ? null : op.run(sdk);
        });
    }

    /** As {@link #post} for the spell checker's engine, creating it if needed (that creation runs on the loader). */
    public static void postSecondary(String site, SdkOp op) {
        EngineThreads.post(() -> {
            final NuanceSDK sdk = NuanceSDKManager.getSecondary();
            if (sdk != null) op.run(sdk);
            else if (BuildConfig.DEBUG) Log.w(TAG, site + ": no secondary engine");
        });
    }

    /** As {@link #query} for the spell checker's engine, creating it if needed. */
    public static <T> T querySecondary(String site, long timeoutMs, T fallback, SdkQuery<T> op) {
        return EngineThreads.query(() -> {
            final NuanceSDK sdk = NuanceSDKManager.getSecondary();
            return sdk == null ? fallback : op.run(sdk);
        }, timeoutMs, fallback);
    }

    /** As {@link #load} for the spell checker's engine, creating it if needed. */
    public static <T> Future<T> loadSecondary(String site, SdkQuery<T> op) {
        return EngineThreads.load(() -> {
            final NuanceSDK sdk = NuanceSDKManager.getSecondary();
            return sdk == null ? null : op.run(sdk);
        });
    }

    /** As {@link #load} for the spell checker's engine, but a no-op when it is not currently loaded. */
    public static <T> Future<T> loadPeekSecondary(String site, SdkQuery<T> op) {
        return EngineThreads.load(() -> {
            final NuanceSDK sdk = NuanceSDKManager.peekSecondary();
            return sdk == null ? null : op.run(sdk);
        });
    }
}
