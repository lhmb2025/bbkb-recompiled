package dev.bbkb.ime.core.device.config.model;

/**
 * How a physical key is routed.
 * <ul>
 *   <li>{@link #CHARACTER}: normal key, through onKeyDown to the IME.</li>
 *   <li>{@link #MODIFIER}: Shift/Alt/Ctrl, the modifier state machine.</li>
 *   <li>{@link #BOARD_EMOJI}, {@link #BOARD_VOICE}, {@link #BOARD_SYM}: open a board, consumed at accessibility level.</li>
 *   <li>{@link #FUNCTION}: passes through as its treatAs keycode.</li>
 *   <li>{@link #MULTIFUNCTION}: user-chosen action (MultifunctionKeyHandler); never consumed at
 *       accessibility level, since the action may be a held Ctrl.</li>
 *   <li>{@link #GESTURE_SWIPE_LEFT}: firmware key synthesised from a left swipe (Titan 2: keycode 322
 *       on Android 15, 404 on 16); handed to the gesture arbiter, consumed and never typed.</li>
 * </ul>
 */
public enum KeyRole {
    CHARACTER,
    MODIFIER,
    BOARD_EMOJI,
    BOARD_VOICE,
    BOARD_SYM,
    FUNCTION,
    MULTIFUNCTION,
    GESTURE_SWIPE_LEFT;

    /** True if the key opens a board and never reaches onKeyDown. */
    public boolean isConsumedAtAccessibilityLevel() {
        return this == BOARD_EMOJI || this == BOARD_VOICE || this == BOARD_SYM;
    }

    /** Case-insensitive XML value to role, or null if unrecognized. */
    public static KeyRole fromString(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
