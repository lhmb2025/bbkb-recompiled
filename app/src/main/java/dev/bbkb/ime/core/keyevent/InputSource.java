package dev.bbkb.ime.core.keyevent;

/** Where an input event came from. Ids are the {@code arg1} wire format of MSG_BATCH_INPUT_SUGGESTIONS, so fixed. */
public enum InputSource {
    UNKNOWN(0),
    INTERNAL(1),
    SOFTWARE(2),
    HARDWARE(4);

    private final int sourceId;

    /** @param i the wire id */
    InputSource(int i) {
        this.sourceId = i;
    }

    public int getId() {
        return this.sourceId;
    }

    /** @param i the wire id; unknown ids map to UNKNOWN */
    public static InputSource fromId(int i) {
        switch (i) {
            case 1:
                return INTERNAL;
            case 2:
                return SOFTWARE;
            case 4:
                return HARDWARE;
            default:
                return UNKNOWN;
        }
    }
}
