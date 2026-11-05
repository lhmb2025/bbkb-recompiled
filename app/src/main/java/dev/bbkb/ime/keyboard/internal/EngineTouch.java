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
 * <p>Phase B: {@link TouchSessionGate} decides, before the post, whether an op can do anything
 * in the engine. A cancel is posted only for the contact that owns the swipe session, so a
 * cancel meant for another contact can never end a swipe that started after it; repeated
 * cancels, another contact's moves and lift-off, and everything for a contact that was never
 * started are not posted at all. Main thread only.
 *
 * <p>{@link #end} also keeps the main thread's "a swipe word may still be coming" count
 * ({@link NuanceSDKManager#noteGestureEndPosted()}) up from the moment it posts until the op has
 * run, because the deposit seq that {@link NuanceSDKManager#isGesturePending()} otherwise reads
 * is now written on the engine thread.
 */
public final class EngineTouch {

    private static final TouchSessionGate sGate = new TouchSessionGate();

    private EngineTouch() {
    }

    /** A contact that can become a swipe has landed. It takes the session from any other. */
    public static void start(String site, int pointerId, int x, int y, long time) {
        sGate.start(pointerId);
        EngineOps.post(site, sdk -> sdk.touchStart(pointerId, x, y, time));
    }

    public static void move(String site, int pointerId, int x, int y, long time) {
        if (!sGate.move(pointerId)) return;
        EngineOps.post(site, sdk -> sdk.touchMove(pointerId, x, y, time));
    }

    /**
     * Drops what the engine holds for this contact. Not posted when another contact owns the
     * session, or when there is nothing to drop.
     */
    public static void cancel(String site, long pointerId) {
        if (!sGate.cancel(pointerId)) return;
        EngineOps.post(site, sdk -> sdk.touchCancel(pointerId));
    }

    /**
     * {@link #cancel} for all the contacts of one event: one op, for whichever of them owns
     * the session.
     */
    public static void cancelAll(String site, long[] pointerIds) {
        final long pointerId = sGate.cancelAny(pointerIds);
        if (pointerId == TouchSessionGate.NOTHING) return;
        EngineOps.post(site, sdk -> sdk.touchCancel(pointerId));
    }

    /**
     * Posts the lift-off: the engine decodes the trace and, when it accepts it, the deposit seq
     * is raised in the same op, so the prediction request posted after this sees it. Not posted
     * for a contact that does not own the session: there is nothing of its own to decode.
     */
    public static void end(String site, int pointerId, int x, int y, long time) {
        if (!sGate.end(pointerId)) return;
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

    /** Tests start from an engine with no session. */
    public static void resetForTests() {
        sGate.reset();
    }
}
