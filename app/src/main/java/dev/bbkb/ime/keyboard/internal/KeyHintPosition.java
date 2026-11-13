package dev.bbkb.ime.keyboard.internal;

/**
 * Which corner of a key shows its small hint label or icon, or {@link #HIDDEN}. Used for the
 * long-press hint declared per key in layout XML (default HIDDEN), for the popup hint letter
 * (always bottom-right), and for the secondary icon {@code KeyboardSwitcher} puts top-right on the
 * UIM toggle (-23) and language switch (-10) keys. Values are the XML attribute's integers.
 */
public enum KeyHintPosition {
    HIDDEN("hidden", 0),
    TOP_RIGHT_CORNER("topRightCorner", 1),
    TOP_LEFT_CORNER("topLeftCorner", 2),
    BOTTOM_RIGHT_CORNER("bottomRightCorner", 3),
    BOTTOM_LEFT_CORNER("bottomLeftCorner", 4);


    private final String label;

    private final int value;

    /**
     * @param label the XML name
     * @param value the XML integer value
     */
    KeyHintPosition(String label, int value) {
        this.label = label;
        this.value = value;
    }

    public int getValue() {
        return this.value;
    }

    @Override // java.lang.Enum
    public String toString() {
        return this.label;
    }

    /** @param value the XML integer value */
    public static KeyHintPosition fromValue(int value) {
        switch (value) {
            case 0:
                return HIDDEN;
            case 1:
                return TOP_RIGHT_CORNER;
            case 2:
                return TOP_LEFT_CORNER;
            case 3:
                return BOTTOM_RIGHT_CORNER;
            case 4:
                return BOTTOM_LEFT_CORNER;
            default:
                throw new IllegalArgumentException("Invalid display style value: " + value);
        }
    }
}
