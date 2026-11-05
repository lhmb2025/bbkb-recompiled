package dev.bbkb.ime.keyboard.internal;

/**
 * Which touch ops are worth posting to the engine thread (Wave 3 Phase B).
 *
 * <p>The engine has ONE swipe session: one path buffer, owned by the contact whose
 * {@code touchStart} came last (the owned module's rule, kdb_trace.c L1). It drops a
 * {@code touchMove} from any other contact and ignores another contact's {@code touchEnd}, but
 * its {@code touchCancel} clears the buffer whatever pointer id it is given. So a cancel meant
 * for one contact used to end another contact's swipe: a second finger touching the on-screen
 * keyboard during a swipe, a finger lifting after the next one had landed, or a keypad contact
 * the gesture arbiter had already taken.
 *
 * <p>This is the same session, kept on the main thread, where every touch op is posted from.
 * The engine thread runs the ops in the order they were posted, so what this knows after
 * posting an op is what the engine will know after running it. That lets the decision be made
 * here, before the post, instead of on the engine thread:
 *
 * <ul>
 *   <li>a cancel reaches the engine only for the contact that owns the session (cancellation is
 *       keyed on the contact), or when there is no session but a finished path is still in the
 *       buffer, which a cancel has always cleared;</li>
 *   <li>a second cancel in a row is not posted (the arbiter and the multi-pointer detector ask
 *       on every sample of a contact they have taken);</li>
 *   <li>a move or an end from a contact that does not own the session is not posted: the
 *       engine would drop the one and ignore the other;</li>
 *   <li>with no session at all (swiping is off for the field or the board, so nobody posted a
 *       start) nothing is posted.</li>
 * </ul>
 *
 * <p>Main thread only. Being wrong here is safe in one direction: a session this believes is
 * live when the engine has none (no engine yet, a stray lift the engine cancelled itself) only
 * means an op is posted that does nothing, as before Phase B.
 */
final class TouchSessionGate {

    /** {@link #cancelAny} found nothing to cancel. */
    static final long NOTHING = Long.MIN_VALUE;

    /** A posted start owns the session and no end or cancel has been posted since. */
    private boolean mLive;

    /** The pointer id of that start; meaningful while {@link #mLive}. */
    private long mOwner;

    /** Samples, or a finished swipe's path, may be in the engine's buffer. */
    private boolean mDirty;

    /** A start is always posted: the newest contact takes the session. */
    void start(long pointerId) {
        mLive = true;
        mOwner = pointerId;
        mDirty = true;
    }

    /** Whether to post this contact's move. */
    boolean move(long pointerId) {
        return mLive && mOwner == pointerId;
    }

    /** Whether to post this contact's lift-off for decoding. */
    boolean end(long pointerId) {
        if (!mLive || mOwner != pointerId) return false;
        mLive = false;
        // The decoded path stays readable in the buffer until the next start or cancel.
        mDirty = true;
        return true;
    }

    /** Whether to post a cancel for this contact. */
    boolean cancel(long pointerId) {
        if (mLive) {
            if (mOwner != pointerId) return false;
        } else if (!mDirty) {
            return false;
        }
        mLive = false;
        mDirty = false;
        return true;
    }

    /**
     * A cancel on behalf of every contact in {@code pointerIds} (all the fingers of one keypad
     * event). Returns the pointer id to post the cancel for, or {@link #NOTHING}.
     */
    long cancelAny(long[] pointerIds) {
        if (pointerIds.length == 0) return NOTHING;
        if (mLive) {
            for (long id : pointerIds) {
                if (id == mOwner) {
                    mLive = false;
                    mDirty = false;
                    return id;
                }
            }
            return NOTHING;
        }
        if (!mDirty) return NOTHING;
        mDirty = false;
        return pointerIds[0];
    }

    void reset() {
        mLive = false;
        mOwner = 0;
        mDirty = false;
    }
}
