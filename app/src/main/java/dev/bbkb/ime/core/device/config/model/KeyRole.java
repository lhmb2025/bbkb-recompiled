package dev.bbkb.ime.core.device.config.model;

/**
 * Classifies the role of a physical key, determining how it is routed through the pipeline.
 *
 * <ul>
 *   <li>{@link #CHARACTER} — Normal character key, passes through to onKeyDown/IME pipeline</li>
 *   <li>{@link #MODIFIER} — Shift/Alt/Ctrl, handled by modifier state machine</li>
 *   <li>{@link #BOARD_EMOJI} — Opens emoji board (consumed at accessibility level)</li>
 *   <li>{@link #BOARD_VOICE} — Opens voice input (consumed at accessibility level)</li>
 *   <li>{@link #BOARD_SYM} — Toggles symbol keyboard (consumed at accessibility level)</li>
 *   <li>{@link #FUNCTION} — Special function key, passes through with treatAs keycode</li>
 *   <li>{@link #MULTIFUNCTION} — User-mappable convenience key; its action (Ctrl, emoji board,
 *       language switch, …) is chosen in Settings → Physical keyboard. Never consumed at the
 *       accessibility level: the action may be a held Ctrl modifier, so the key must always
 *       reach the IME key pipeline. See MultifunctionKeyHandler.</li>
 * </ul>
 *
 * @see ScancodeMapping
 */
public enum KeyRole {
    CHARACTER,
    MODIFIER,
    BOARD_EMOJI,
    BOARD_VOICE,
    BOARD_SYM,
    FUNCTION,
    MULTIFUNCTION;

    /**
     * Returns true if this role causes the key to be consumed at the accessibility service level
     * (i.e., it triggers a board/panel and never reaches onKeyDown).
     */
    public boolean isConsumedAtAccessibilityLevel() {
        return this == BOARD_EMOJI || this == BOARD_VOICE || this == BOARD_SYM;
    }

    /**
     * Parse a KeyRole from its XML string representation.
     * @param value The string value from XML (case-insensitive)
     * @return The parsed KeyRole, or null if unrecognized
     */
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
