package dev.bbkb.ime.core.engine;

import android.util.Log;

import com.blackberry.nuanceshim.NuanceSDK;

import java.util.concurrent.Future;

import dev.bbkb.ime.BuildConfig;

/**
 * Engine ops that resolve the engine inside the op, on the thread that runs it, so main never joins
 * an engine start or takes the engine lock. {@link #post} is FIFO with the suggestion requests
 * (never add a delay); {@link #query} waits bounded; {@link #load} runs long holds on the loader
 * (wait on its future only off main). {@code Secondary} variants use the spell checker's engine;
 * {@code PeekSecondary} does nothing when it is not loaded.
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
