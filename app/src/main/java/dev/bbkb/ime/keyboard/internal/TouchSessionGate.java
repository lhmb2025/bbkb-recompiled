package dev.bbkb.ime.keyboard.internal;

/**
 * Main-thread mirror of the engine's single swipe session, deciding which touch ops to post.
 * The engine's session belongs to the latest {@code touchStart}; it drops other contacts' moves
 * and ends, but {@code touchCancel} clears the buffer for any pointer id. So a cancel is posted
 * only for the owner (or to clear a finished path), never twice in a row, and nothing is posted
 * with no session. Ops run FIFO, so this state matches the engine's after each post. Erring
 * toward "live" is safe: it only posts an op that does nothing.
 */
final class TouchSessionGate {
    /** Returned by {@link #cancelAny} when there is nothing to cancel. */
    static final long NOTHING = Long.MIN_VALUE;

    /** A start owns the session and no end or cancel has been posted since. */
    private boolean mLive;

    /** Owner pointer id, meaningful while {@link #mLive}. */
    private long mOwner;

    /** Samples, or a finished swipe's path, may be in the engine's buffer. */
    private boolean mDirty;

    /** A start is always posted: the newest contact takes the session. */
    void start(long pointerId) {
        mLive = true;
        mOwner = pointerId;
        mDirty = true;
    }

    boolean move(long pointerId) {
        return mLive && mOwner == pointerId;
    }

    boolean end(long pointerId) {
        if (!mLive || mOwner != pointerId) return false;
        mLive = false;
        // The decoded path stays readable in the buffer until the next start or cancel.
        mDirty = true;
        return true;
    }

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

    /** Cancel for all the fingers of one keypad event; returns the pointer id to cancel, or {@link #NOTHING}. */
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
