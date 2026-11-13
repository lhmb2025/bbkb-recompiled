package dev.bbkb.ime.core.keyevent;

import android.view.KeyEvent;

import androidx.annotation.Nullable;

import java.util.EnumMap;

/**
 * Owns the key-down to key-up pairing for keys that act on release (emoji, mic, multifunction): the down
 * arms a {@link PendingKeyAction}, the up spends it via {@link #consume}. One slot per action, so two
 * keys arming the same action leave one pending. A full modifier reset discards all pending actions (a
 * swallowed release must not open a board later). Consumption is not gated on {@link #armedBy}: a letter
 * released while the mic key is held opens voice (pinned by BoardKeyPairingCharacterisationTest).
 * Synchronized: resets can arrive from the screen-off receiver's thread. */
public final class BoardKeyPressTracker {

    private static final BoardKeyPressTracker sInstance = new BoardKeyPressTracker();

    /** At most one pending instance per action; the value is the key that armed it. */
    private final EnumMap<PendingKeyAction, ResolvedKey.Identity> mPending =
            new EnumMap<>(PendingKeyAction.class);

    private BoardKeyPressTracker() {
    }

    public static BoardKeyPressTracker getInstance() {
        return sInstance;
    }

    /** Arms {@code action} for the key of {@code downEvent}, replacing any pending instance. */
    public synchronized void arm(KeyEvent downEvent, PendingKeyAction action) {
        mPending.put(action, ResolvedKey.Identity.of(downEvent));
    }

    /** Arm {@code action} for an already-resolved key. */
    public synchronized void arm(ResolvedKey key, PendingKeyAction action) {
        mPending.put(action, key.identity());
    }

    /** Whether {@code action} is pending, without spending it. */
    public synchronized boolean isArmed(PendingKeyAction action) {
        return mPending.containsKey(action);
    }

    /** @return true if {@code action} was pending (and is now spent) */
    public synchronized boolean consume(PendingKeyAction action) {
        return mPending.remove(action) != null;
    }

    /** The key that armed {@code action}, or {@code null} if it is not pending. */
    @Nullable
    public synchronized ResolvedKey.Identity armedBy(PendingKeyAction action) {
        return mPending.get(action);
    }

    /** Called from the tracker's resetModifiers; only a full reset clears pending actions. */
    public synchronized void onModifiersReset(ModifierResetReason reason) {
        if (reason.scope() == ModifierResetReason.Scope.ALL) {
            mPending.clear();
        }
    }

    public synchronized void clearPendingActions() {
        mPending.clear();
    }

    @Override
    public synchronized String toString() {
        return "BoardKeyPressTracker" + mPending;
    }
}
