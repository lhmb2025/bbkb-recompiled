package dev.bbkb.ime.core.keyevent;

/**
 * Where an input event came from. The ids are the wire format for
 * {@code UIUpdateHandler.MSG_BATCH_INPUT_SUGGESTIONS}'s {@code arg1}, so they are fixed.
 *
 * <p>Audit W1-F: {@code EXTERNAL} (id 3) was removed — nothing named it, and the only
 * {@link #getId()} producer ({@code UIUpdateHandler.postBatchInputSuggestions}) is only ever fed
 * HARDWARE / SOFTWARE / INTERNAL / UNKNOWN, so {@link #fromId(int)} could never yield it.
 */
public enum InputSource {
    UNKNOWN(0),
    INTERNAL(1),
    SOFTWARE(2),
    HARDWARE(4);

    private final int sourceId;

    InputSource(int i) {
        this.sourceId = i;
    }

    public int getId() {
        return this.sourceId;
    }

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
