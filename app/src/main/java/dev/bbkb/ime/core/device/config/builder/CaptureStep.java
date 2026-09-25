package dev.bbkb.ime.core.device.config.builder;

import android.view.KeyEvent;

import androidx.annotation.Nullable;

import dev.bbkb.ime.core.device.config.model.KeyRole;

/**
 * The keys the guided capture asks for, in the order it asks for them.
 *
 * <p>Each step knows what the key it captures <em>is</em> — the {@link KeyRole} the emitted
 * {@code <key>} element carries, the {@code treatAs} keycode name to fall back on when the
 * hardware's own keycode is not a recognisable one, and the board id a {@code BOARD_*} role
 * needs. What it deliberately does NOT know is the scancode or the keycode: those are the two
 * things that have to be observed on the hardware in front of the user.
 *
 * <p>That is the whole reason this feature exists. The Minimal Phone MP01's Sym key is scancode
 * 249 on every ROM, but its keycode was {@code KEYCODE_ALT_RIGHT} on the November 2025 ROM and is
 * {@code KEYCODE_SYM} on {@code MP01_20260104_1412} — an assumption either way is wrong on half
 * the fleet.
 *
 * <p>The first six steps are keys every PKB handset has; the last three are the convenience keys
 * only some do, so they are {@link #optional()} and the screen's "Skip" is the expected answer
 * when the key is not on the bezel.
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

    /** Board ids the app's own board router uses; they are app constants, not device facts. */
    public static final class BoardIds {
        public static final int EMOJI = -11;
        public static final int VOICE = -27;

        private BoardIds() {}
    }

    /** The action a captured MULTIFUNCTION key starts life with, until the user repoints it. */
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
     * The {@code treatAs} attribute for a key captured at this step.
     *
     * <p>The observed keycode wins when the framework has a name for it — that is the ROM telling
     * us what it thinks the key is, and {@code treatAs} exists to normalise, not to overrule. It
     * is ignored for a modifier step when the ROM reported something that is not a modifier at
     * all (the MP01's Sym-as-ALT_RIGHT case in reverse), and for {@link #SPEED}, whose whole
     * point is that it has no meaning until the user gives it one.
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
     * True when the observed keycode is a plausible identity for this step's key. A modifier step
     * only accepts a modifier keycode; every other step accepts whatever arrived, because a
     * device's Sym key legitimately reports {@code KEYCODE_SYM}, {@code KEYCODE_ALT_RIGHT} or a
     * vendor keycode depending on the ROM's keylayout.
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

    /** The default-action attribute for a MULTIFUNCTION capture, or null for every other step. */
    @Nullable
    public String defaultAction() {
        return role == KeyRole.MULTIFUNCTION ? MULTIFUNCTION_DEFAULT_ACTION : null;
    }
}
