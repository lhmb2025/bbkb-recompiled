package dev.bbkb.ime.keyboard.internal;

import com.blackberry.nuanceshim.NuanceSDK;

import dev.bbkb.ime.core.engine.EngineOps;
import dev.bbkb.ime.core.engine.EngineThreads;
import dev.bbkb.ime.core.engine.NuanceSDKManager;

/**
 * The engine's touch samples, posted to the engine thread (Wave 3 Phase A). Callers compute
 * every coordinate on the main thread and pass values; the ops only forward them, in the order
 * they were posted, ahead of any suggestion request posted after them.
 *
 * <p>{@link #end} also keeps the main thread's "a swipe word may still be coming" count
 * ({@link NuanceSDKManager#noteGestureEndPosted()}) up from the moment it posts until the op has
 * run, because the deposit seq that {@link NuanceSDKManager#isGesturePending()} otherwise reads
 * is now written on the engine thread.
 */
public final class EngineTouch {

    private EngineTouch() {
    }

    public static void start(String site, int pointerId, int x, int y, long time) {
        EngineOps.post(site, sdk -> sdk.touchStart(pointerId, x, y, time));
    }

    public static void move(String site, int pointerId, int x, int y, long time) {
        EngineOps.post(site, sdk -> sdk.touchMove(pointerId, x, y, time));
    }

    public static void cancel(String site, long pointerId) {
        EngineOps.post(site, sdk -> sdk.touchCancel(pointerId));
    }

    /**
     * Posts the lift-off: the engine decodes the trace and, when it accepts it, the deposit seq
     * is raised in the same op, so the prediction request posted after this sees it.
     */
    public static void end(String site, int pointerId, int x, int y, long time) {
        NuanceSDKManager.noteGestureEndPosted();
        // Not EngineOps.post: that drops the op when there is no engine, and the count must come
        // down whatever happens. No engine means no deposit and nothing to commit, the same
        // outcome as a gesture the engine did not take.
        EngineThreads.post(() -> {
            try {
                final NuanceSDK sdk = NuanceSDKManager.sdkOrWarn(site);
                if (sdk != null && sdk.touchEnd(pointerId, x, y, time)) {
                    NuanceSDKManager.noteGestureDeposit();
                }
            } finally {
                NuanceSDKManager.noteGestureEndResolved();
            }
        });
    }
}
