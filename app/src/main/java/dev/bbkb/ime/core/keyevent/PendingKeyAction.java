package dev.bbkb.ime.core.keyevent;

/**
 * An action a key arms on key-down and performs on key-up (emoji, mic, multifunction). These keys toggle,
 * so acting on the down would flap on repeat and clear state the key-up decision reads (see
 * {@link ResolvedKey#isBoardKey()}). {@link BoardKeyPressTracker} owns the pairing.
 */
public enum PendingKeyAction {
    /** Armed by a {@code BOARD_EMOJI} key or pseudo-keycode 666. */
    EMOJI_BOARD,

    /**
     * Voice input or the voice board. Armed by a {@code BOARD_VOICE} key, mic key code 7,
     * {@code KEYCODE_VOICE_ASSIST} on the mic pseudo-scancode, or pseudo-keycode 667.
     */
    VOICE_INPUT,

    /**
     * A multifunction key's other actions: voice and emoji arm their own slots, and Ctrl arms nothing
     * because remapKeyEvent already rewrote the event to KEYCODE_CTRL_LEFT.
     */
    MULTIFUNCTION,
}
