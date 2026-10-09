package dev.bbkb.ime.core.device.config.model;

import androidx.annotation.Nullable;

/**
 * A firmware behaviour the key pipeline has to tolerate on one device, declared in its config as
 * {@code <quirk name="..."/>} under {@code <device>}. A quirk describes the device; the code that
 * honours it says what it does about it.
 */
public enum DeviceQuirk {

    /**
     * The Fn key arrives as {@code KEYCODE_CTRL_LEFT}, sends no key-up, and auto-repeats while held
     * (Titan 2 Elite: first repeat after about 400 ms). {@code ControlModeController} then treats
     * Ctrl as a one-shot modifier for the next key and never latches its sticky Ctrl mode from the
     * repeats, which on any other device mean "Ctrl held alone".
     */
    FN_NO_KEY_UP("fn-no-key-up");

    private final String tag;

    DeviceQuirk(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }

    /** The quirk a {@code name} attribute names, or null if unrecognised. */
    @Nullable
    public static DeviceQuirk of(@Nullable String name) {
        if (name == null) return null;
        final String trimmed = name.trim();
        for (DeviceQuirk q : values()) {
            if (q.tag.equalsIgnoreCase(trimmed)) return q;
        }
        return null;
    }
}
