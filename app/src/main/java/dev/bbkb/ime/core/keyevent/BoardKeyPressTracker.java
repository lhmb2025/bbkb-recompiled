package dev.bbkb.ime.core.keyevent;

import android.view.KeyEvent;

import androidx.annotation.Nullable;

import java.util.EnumMap;

/**
 * The one owner of the key-DOWN &rarr; key-UP pairing for keys whose action fires on release.
 *
 * <p>Phase 1g of the typing-path consolidation. The emoji, mic and MULTIFUNCTION keys do nothing
 * on the way down: the down arms a {@link PendingKeyAction} and the up performs it. Before this
 * class that pairing travelled through three mutable public {@code boolean} fields on the
 * {@code InputMethodHelper} singleton — a class in {@code core.textinput} that has nothing to do
 * with key events — armed by {@link KeyEventConverter} and consumed by {@link KeyEventProcessor},
 * owned by neither, documented in three places, and reachable (and writable) from anywhere in the
 * app. It is the state {@link ResolvedKey}'s javadoc named as the reason it was "the identity half
 * of a typed event" rather than the event.
 *
 * <h3>The contract</h3>
 * <ul>
 *   <li><b>One slot per action, not one per key.</b> {@link #arm} overwrites, so arming the same
 *       action twice leaves one pending action and one release spends it. This is deliberately the
 *       shape of the three booleans it replaces: two emoji-role keys held together used to set one
 *       flag, and they still arm one slot.</li>
 *   <li><b>The arming key's identity is recorded</b> ({@link #armedBy}) and each slot is keyed by
 *       it, so "which key is this pending action waiting for" is an answerable question for the
 *       first time. Consumption, however, is deliberately <em>not</em> gated on it — see below.</li>
 *   <li><b>{@link #consume} is the only way to spend a pending action</b>, and it reports whether
 *       there was one. Nothing reads a slot and clears it separately.</li>
 *   <li><b>A full modifier reset discards every pending action</b>
 *       ({@link #onModifiersReset}). The IME window hidden, input finished, the editor switched,
 *       the screen off, the keyboard reloaded: the key that armed the action is, as far as this
 *       process knows, gone. Before Phase 1g nothing cleared these flags but the key-up that
 *       consumed them, so a press whose release was swallowed left a board waiting to open on the
 *       next unrelated key release. A partial reset (the Alt page, a spent manual Shift) is not
 *       that event and leaves pending actions alone.</li>
 * </ul>
 *
 * <h3>What consumption does NOT ask</h3>
 * The voice and multifunction branches in {@link KeyEventProcessor} ask only "is this action
 * pending, and is this event physical" — never "is this the key that armed it". So a letter
 * released while the mic key is still held opens the voice board. That is preserved here, quirk
 * and all, because gating consumption on {@link #armedBy} is a behaviour change and Phase 1g
 * authorised exactly one (the Sym pre-pass). {@code BoardKeyPairingCharacterisationTest} pins the
 * quirk so a later pass has to decide about it on purpose.
 *
 * <h3>Threading</h3>
 * Arming and consuming happen on the key thread; {@link #onModifiersReset} can arrive from the
 * screen-off receiver's thread. Every method is {@code synchronized} on the instance, which is
 * strictly more than the plain non-volatile booleans offered.
 */
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

    /**
     * Arm {@code action} for the key {@code downEvent} belongs to, replacing any pending instance
     * of the same action.
     *
     * <p>Takes the event rather than a {@link ResolvedKey} on purpose: the arming sites are inside
     * {@link KeyEventConverter}, which has already resolved the mapping for its own reasons, and
     * identity here is just the key code and scancode — nothing that needs a second lookup.
     */
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

    /**
     * Spend {@code action} if it is pending.
     *
     * @return {@code true} if it was pending (and is now not), {@code false} if there was nothing
     *         to spend
     */
    public synchronized boolean consume(PendingKeyAction action) {
        return mPending.remove(action) != null;
    }

    /** The key that armed {@code action}, or {@code null} if it is not pending. */
    @Nullable
    public synchronized ResolvedKey.Identity armedBy(PendingKeyAction action) {
        return mPending.get(action);
    }

    /**
     * The reset contract. Called from {@code PhysicalKeyboardStateTracker.resetModifiers}, the one
     * place physical-keyboard key state is cleared, so that the pending actions go with the
     * held-key set they belong to. The policy — which reasons clear them — lives here, with the
     * owner of the state, and not in the tracker.
     */
    public synchronized void onModifiersReset(ModifierResetReason reason) {
        if (reason.scope() == ModifierResetReason.Scope.ALL) {
            mPending.clear();
        }
    }

    /** Drop every pending action. */
    public synchronized void clearPendingActions() {
        mPending.clear();
    }

    @Override
    public synchronized String toString() {
        return "BoardKeyPressTracker" + mPending;
    }
}
