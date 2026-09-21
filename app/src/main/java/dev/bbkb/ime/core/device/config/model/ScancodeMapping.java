package dev.bbkb.ime.core.device.config.model;

/**
 * Represents a single scancode/keycode → role mapping from device XML configuration.
 *
 * <p>Each instance maps a raw hardware scancode and/or Android keycode to:
 * <ul>
 *   <li>{@link #role} — determines how the key is routed (consumed at accessibility level, or passed through)</li>
 *   <li>{@link #treatAs} — optional normalized Android KEYCODE_* name</li>
 *   <li>{@link #altChar} — optional character to output when Alt is active</li>
 *   <li>{@link #boardId} — optional board identifier for BOARD_* roles</li>
 * </ul>
 *
 * <p>Matching rules:
 * <ul>
 *   <li>If both {@link #rawScanCode} and {@link #rawKeyCode} are set, both must match (AND logic)</li>
 *   <li>If only one is set, that one is used for matching</li>
 *   <li>A value of {@link #UNSET} means "don't care" for that field</li>
 * </ul>
 *
 * @see KeyRole
 * @see ScancodeMappingResolver (in resolver package)
 */
public class ScancodeMapping {

    /** Sentinel value indicating a scancode/keycode field is not set. */
    public static final int UNSET = -1;

    /** Raw hardware scancode to match (or {@link #UNSET} if not specified). */
    public int rawScanCode = UNSET;

    /** Android keycode to match (or {@link #UNSET} if not specified). */
    public int rawKeyCode = UNSET;

    /** Normalized Android KEYCODE_* name (e.g., "KEYCODE_SYM"). Optional. */
    public String treatAs;

    /** The role that determines pipeline routing. Required. */
    public KeyRole role;

    /** Character to output when Alt is active. '\0' means none. */
    public char altChar = '\0';

    /** Board ID for BOARD_* roles (e.g., -11 for emoji, -27 for voice). 0 means none. */
    public int boardId = 0;

    /**
     * Default action for {@link KeyRole#MULTIFUNCTION} keys (e.g., "voice_input"), used until
     * the user picks one in Settings. Action ids are defined in MultifunctionKeyHandler.
     */
    public String defaultAction;

    /** Human-readable description for logging/debugging. */
    public String notes;

    public ScancodeMapping() {
        // Default constructor
    }

    /**
     * Check if this mapping matches the given scancode and keycode.
     *
     * @param scanCode the raw hardware scancode from the KeyEvent
     * @param keyCode  the Android keycode from the KeyEvent
     * @return true if this mapping matches
     */
    public boolean matches(int scanCode, int keyCode) {
        boolean scanMatch = (rawScanCode == UNSET) || (rawScanCode == scanCode);
        boolean keyMatch = (rawKeyCode == UNSET) || (rawKeyCode == keyCode);

        // At least one must be specified (not both UNSET)
        if (rawScanCode == UNSET && rawKeyCode == UNSET) {
            return false;
        }

        return scanMatch && keyMatch;
    }

    /**
     * Returns true if this mapping has a valid alt character defined.
     */
    public boolean hasAltChar() {
        return altChar != '\0';
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ScancodeMapping{");
        if (rawScanCode != UNSET) sb.append("sc=").append(rawScanCode);
        if (rawKeyCode != UNSET) {
            if (rawScanCode != UNSET) sb.append(", ");
            sb.append("kc=").append(rawKeyCode);
        }
        sb.append(", role=").append(role);
        if (treatAs != null) sb.append(", treatAs=").append(treatAs);
        if (altChar != '\0') sb.append(", altChar='").append(altChar).append("'");
        if (boardId != 0) sb.append(", board=").append(boardId);
        if (defaultAction != null) sb.append(", defaultAction=").append(defaultAction);
        if (notes != null) sb.append(", notes='").append(notes).append("'");
        sb.append('}');
        return sb.toString();
    }
}
