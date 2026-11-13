package dev.bbkb.ime.core.device.config.model;

/**
 * One {@code <scancode-mappings>} entry: a scancode and/or keycode mapped to a {@link KeyRole} plus
 * optional treat-as keycode, Alt character and board id. Set fields must all match; {@link #UNSET}
 * means "don't care", and an entry with neither set matches nothing.
 */
public class ScancodeMapping {
    public static final int UNSET = -1;

    public int rawScanCode = UNSET;

    public int rawKeyCode = UNSET;

    /** Normalized KEYCODE_* name (e.g. "KEYCODE_SYM"), or null. */
    public String treatAs;

    /** Required. */
    public KeyRole role;

    /** Alt character; '\0' means none. */
    public char altChar = '\0';

    /** Board id for BOARD_* roles (e.g. -11 emoji, -27 voice); 0 means none. */
    public int boardId = 0;

    /** Default {@link KeyRole#MULTIFUNCTION} action (ids in MultifunctionKeyHandler) until the user picks one. */
    public String defaultAction;

    public String notes;

    public ScancodeMapping() {
        // Fields are filled in by DeviceInputMappingParser.
    }

    public boolean matches(int scanCode, int keyCode) {
        boolean scanMatch = (rawScanCode == UNSET) || (rawScanCode == scanCode);
        boolean keyMatch = (rawKeyCode == UNSET) || (rawKeyCode == keyCode);

        if (rawScanCode == UNSET && rawKeyCode == UNSET) {
            return false;
        }

        return scanMatch && keyMatch;
    }

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
