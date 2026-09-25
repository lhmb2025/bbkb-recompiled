package dev.bbkb.ime.core.keyevent;

/**
 * The one notification path for "the active modifiers changed".
 *
 * <p>{@code PhysicalKeyboardStateTracker} publishes through this and nothing else, from a single
 * method, so a consumer cannot end up with a second source of truth the way
 * {@link ModifierStatusBarUpdater} did: it caches what it last posted, and every transition the
 * tracker forgot to report (a sticky modifier spent by a character) left that cache claiming an
 * icon that was no longer correct — and, because it only posts on a change, the next press of the
 * same modifier then posted nothing at all.
 */
public interface ModifierStateListener {

    /**
     * @param state the modifiers as they are now
     * @param force post even if nothing changed since the last notification. The status-bar slot
     *              belongs to the system, which clears it on every IME unbind and drops posts
     *              made before the IME's privileged operations are attached, so the IME needs a
     *              way to re-assert an unchanged state (window shown, input view started).
     */
    void onModifierStateChanged(ModifierState state, boolean force);
}
