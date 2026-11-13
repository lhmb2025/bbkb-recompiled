package dev.bbkb.ime.core.device.config.model;

import androidx.annotation.Nullable;

/** A device behaviour to tolerate, declared as {@code <quirk name="..."/>} under {@code <device>}. */
public enum DeviceQuirk {

    /**
     * Fn arrives as {@code KEYCODE_CTRL_LEFT} with no key-up and auto-repeats (Titan 2 Elite, ~400 ms).
     * {@code ControlModeController} treats it as one-shot and never latches sticky Ctrl from the repeats.
     */
    FN_NO_KEY_UP("fn-no-key-up"),

    /**
     * E-ink screen (Minimal Phone MP01): the on-screen keyboard skips cosmetic repaints (key highlights,
     * fades, animated previews, gesture trail) in MainKeyboardView.
     */
    EINK_DISPLAY("eink-display");

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
