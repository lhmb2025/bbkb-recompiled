package dev.bbkb.ime.core.keyevent;

/**
 * An action a key arms on the way DOWN and performs on the way UP.
 *
 * <p>Three keys work this way — the emoji key, the mic/voice key and a user-mappable
 * MULTIFUNCTION key — and they have to, because what they do is toggle something. Toggling on the
 * key-down and again on the repeat would flap the board, and disabling cursor mode or dismissing
 * the open boards on the down would clear the very state the key-up decision reads (the historical
 * toggle-reopen fight recorded on {@link ResolvedKey#isBoardKey()}). So the down arms, and the up
 * acts.
 *
 * <p>{@link BoardKeyPressTracker} owns that pairing. Before Phase 1g it lived in three mutable
 * public {@code boolean} fields on the {@code InputMethodHelper} singleton — armed by
 * {@link KeyEventConverter}, consumed by {@link KeyEventProcessor}, owned by neither, and never
 * cleared by anything but the key-up that spent them.
 */
public enum PendingKeyAction {

    /** Open (or close) the emoji board. Armed by a {@code BOARD_EMOJI} key or pseudo-keycode 666. */
    EMOJI_BOARD,

    /**
     * Start voice input, or open the voice board. Armed by a {@code BOARD_VOICE} key, the
     * BlackBerry mic key code 7, {@code KEYCODE_VOICE_ASSIST} on the mic pseudo-scancode, or
     * pseudo-keycode 667.
     */
    VOICE_INPUT,

    /**
     * Dispatch a MULTIFUNCTION key's user-configured action on release.
     *
     * <p>Only the actions that have nowhere else to go: the voice and emoji actions arm
     * {@link #VOICE_INPUT} / {@link #EMOJI_BOARD} instead, so that a multifunction key set to one
     * of them behaves exactly like a dedicated key, and the Ctrl action arms nothing at all
     * because {@code BlackBerryIME.remapKeyEvent} has already rewritten the event to
     * {@code KEYCODE_CTRL_LEFT}.
     */
    MULTIFUNCTION,
}
