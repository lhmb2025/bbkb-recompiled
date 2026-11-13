package dev.bbkb.ime.keyboard.internal;

import com.blackberry.nuanceshim.NuanceSDK;

import dev.bbkb.ime.core.engine.EngineOps;
import dev.bbkb.ime.core.engine.EngineThreads;
import dev.bbkb.ime.core.engine.NuanceSDKManager;

/**
 * Posts touch samples to the engine thread in order, ahead of any later suggestion request.
 * Coordinates are computed by callers on the main thread. {@link TouchSessionGate} filters out
 * ops that cannot do anything. Main thread only.
 */
public final class EngineTouch {

    private static final TouchSessionGate sGate = new TouchSessionGate();

    private EngineTouch() {
        // Static only.
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

    /** Drops what the engine holds for this contact, if it owns the session. */
    public static void cancel(String site, long pointerId) {
        if (!sGate.cancel(pointerId)) return;
        EngineOps.post(site, sdk -> sdk.touchCancel(pointerId));
    }

    /** {@link #cancel} for all the contacts of one event, as one op. */
    public static void cancelAll(String site, long[] pointerIds) {
        final long pointerId = sGate.cancelAny(pointerIds);
        if (pointerId == TouchSessionGate.NOTHING) return;
        EngineOps.post(site, sdk -> sdk.touchCancel(pointerId));
    }

    /**
     * Posts the lift-off for decoding; an accepted trace raises the deposit seq in the same op so
     * the next prediction request sees it. The pending count stays up from post until the op runs,
     * because the seq {@link NuanceSDKManager#isGesturePending()} reads is written on the engine thread.
     */
    public static void end(String site, int pointerId, int x, int y, long time) {
        if (!sGate.end(pointerId)) return;
        NuanceSDKManager.noteGestureEndPosted();
        // Not EngineOps.post: that drops the op with no engine, and the count must still come down.
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
