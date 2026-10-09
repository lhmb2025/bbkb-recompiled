package dev.bbkb.ime.core.device.touch.shizuku;

/**
 * EvdevUserService -> IME. Oneway, so the reader thread never waits on the IME, and calls on
 * one callback object arrive in the order they were sent.
 */
oneway interface IEvdevCallback {
    /**
     * Raw evdev records, EvdevPacking.WORDS_PER_EVENT longs each (CLOCK_MONOTONIC microseconds,
     * then type/code/value), always ending on a SYN_REPORT so a frame is never split.
     */
    void onEvents(in long[] packed);

    /**
     * The stream ended and nothing more will be sent. `reason` is one of the EvdevUserService
     * REASON_* values: "device_gone" (the node vanished, e.g. firmware re-enumeration),
     * "closed" (close(), a newer open() or destroy()), "read_error:<errno>".
     */
    void onClosed(String reason);
}
