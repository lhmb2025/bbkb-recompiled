package dev.bbkb.ime.core.keyevent;

/**
 * The only notification path for modifier changes, published from one method of
 * {@code PhysicalKeyboardStateTracker}, so a caching consumer never misses a transition.
 */
public interface ModifierStateListener {

    /**
     * @param force post even if unchanged: the system clears the status slot on unbind and drops early
     *              posts, so the IME re-asserts on window shown and input view started
     */
    void onModifierStateChanged(ModifierState state, boolean force);

    /**
     * A lifecycle reset ended the session; called after the reset's own notification. A consumer that
     * skips unchanged posts must honour the next {@code force} once.
     */
    default void onInputSessionEnded() {}
}
