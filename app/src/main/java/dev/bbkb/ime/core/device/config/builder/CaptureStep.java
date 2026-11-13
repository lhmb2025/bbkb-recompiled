package dev.bbkb.ime.core.device.config.builder;

import android.view.KeyEvent;

import androidx.annotation.Nullable;

import dev.bbkb.ime.core.device.config.model.KeyRole;

/**
 * The keys guided capture asks for, in order. Each step carries the {@link KeyRole}, fallback
 * {@code treatAs} name and board id; scancode and keycode are always observed because keycodes
 * differ between ROMs. The last three steps are {@link #optional()} convenience keys.
 */
public enum CaptureStep {

    SYM(KeyRole.BOARD_SYM, "KEYCODE_SYM", 0, false),
    ALT(KeyRole.MODIFIER, "KEYCODE_ALT_RIGHT", 0, false),
    SHIFT(KeyRole.MODIFIER, "KEYCODE_SHIFT_LEFT", 0, false),
    ENTER(KeyRole.FUNCTION, "KEYCODE_ENTER", 0, false),
    BACKSPACE(KeyRole.FUNCTION, "KEYCODE_DEL", 0, false),
    SPACE(KeyRole.CHARACTER, "KEYCODE_SPACE", 0, false),
    /** The KEY2's convenience/"Speed" key: no printed character, so the user picks its action. */
    SPEED(KeyRole.MULTIFUNCTION, null, 0, true),
    EMOJI(KeyRole.BOARD_EMOJI, "KEYCODE_EMOJI", BoardIds.EMOJI, true),
    MIC(KeyRole.BOARD_VOICE, "KEYCODE_MIC", BoardIds.VOICE, true);

    /** App board-router ids, not device facts. */
    public static final class BoardIds {
        public static final int EMOJI = -11;
        public static final int VOICE = -27;

        private BoardIds() {}
    }

    /** Initial action of a captured MULTIFUNCTION key. */
    public static final String MULTIFUNCTION_DEFAULT_ACTION = "emoji_board";

    private final KeyRole role;
    @Nullable private final String defaultTreatAs;
    private final int boardId;
    private final boolean optional;

    CaptureStep(KeyRole role, @Nullable String defaultTreatAs, int boardId, boolean optional) {
        this.role = role;
        this.defaultTreatAs = defaultTreatAs;
        this.boardId = boardId;
        this.optional = optional;
    }

    public KeyRole role() {
        return role;
    }

    public int boardId() {
        return boardId;
    }

    public boolean optional() {
        return optional;
    }

    /**
     * The {@code treatAs} attribute: the observed keycode's name when it has one ({@code treatAs}
     * normalises, not overrules), except a non-modifier on a modifier step; none for {@link #SPEED}.
     */
    @Nullable
    public String treatAsFor(int observedKeyCode) {
        if (this == SPEED) {
            return null;
        }
        final String observedName = KeyCodeNames.of(observedKeyCode);
        if (observedName != null && acceptsObservedKeyCode(observedKeyCode)) {
            return observedName;
        }
        return defaultTreatAs;
    }

    /**
     * Modifier steps accept only modifier keycodes; others accept anything (a Sym key may report
     * {@code KEYCODE_SYM}, {@code KEYCODE_ALT_RIGHT} or a vendor code depending on the keylayout).
     */
    private boolean acceptsObservedKeyCode(int keyCode) {
        if (this == ALT) {
            return keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT;
        }
        if (this == SHIFT) {
            return keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT;
        }
        return true;
    }

    /** The default-action attribute for a MULTIFUNCTION capture, else null. */
    @Nullable
    public String defaultAction() {
        return role == KeyRole.MULTIFUNCTION ? MULTIFUNCTION_DEFAULT_ACTION : null;
    }
}
