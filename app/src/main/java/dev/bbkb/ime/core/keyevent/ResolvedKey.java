package dev.bbkb.ime.core.keyevent;

import android.view.KeyEvent;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import dev.bbkb.ime.core.device.config.model.KeyRole;
import dev.bbkb.ime.core.device.config.model.ScancodeMapping;
import dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver;
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier;
import dev.bbkb.ime.core.device.state.MetaKeyStateTracker;

/**
 * Key identity (key code, scancode, pseudo-keycodes, device-config role), resolved once per
 * {@link KeyEvent} so branches cannot disagree about which key was pressed. Holds no modifier
 * state (see {@link ModifierState}) and no per-press pairing (see {@link BoardKeyPressTracker}).
 * Built per event on the key thread; not thread safe. The multifunction action is lazy.
 */
public final class ResolvedKey {

    /**
     * Emoji pseudo-keycode: the Minimal Phone's raw {@code .kl} scancode, which its ROM passes as
     * the key code. Always treated as the emoji key, whatever the device config says.
     */
    public static final int PSEUDO_KEYCODE_EMOJI = 666;

    /** Mic/voice pseudo-keycode, like {@link #PSEUDO_KEYCODE_EMOJI}. */
    public static final int PSEUDO_KEYCODE_VOICE = 667;

    /** The emoji board's own key code, used when the device config names no board id. */
    public static final int DEFAULT_EMOJI_BOARD_ID = -11;

    /** The voice board's own key code, used when the device config names no board id. */
    public static final int DEFAULT_VOICE_BOARD_ID = -27;

    private final int keyCode;
    private final int scanCode;
    private final boolean physical;
    @Nullable private final ScancodeMapping mapping;
    @Nullable private final KeyRole role;

    @Nullable private String multifunctionAction;
    private boolean multifunctionActionResolved;

    private ResolvedKey(int keyCode, int scanCode, boolean physical,
            @Nullable ScancodeMapping mapping) {
        this.keyCode = keyCode;
        this.scanCode = scanCode;
        this.physical = physical;
        this.mapping = mapping;
        this.role = mapping == null ? null : mapping.role;
    }

    /**
     * Call once per event, on the event the path acts on: after a remap that is the remapped
     * event, since a remap can change the key code (a MULTIFUNCTION key as Ctrl).
     */
    public static ResolvedKey of(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        final int scanCode = event.getScanCode();
        return new ResolvedKey(
                keyCode,
                scanCode,
                KeyEventDeviceClassifier.getInstance().isPhysicalKeyboardEvent(event),
                DeviceInputResolver.keyRole(scanCode, keyCode, event.getDeviceId()));
    }

    @VisibleForTesting
    public static ResolvedKey forTest(int keyCode, int scanCode, boolean physical,
            @Nullable ScancodeMapping mapping) {
        return new ResolvedKey(keyCode, scanCode, physical, mapping);
    }

    /** After any remap. */
    public int keyCode() {
        return keyCode;
    }

    /** This, not the key code, identifies a physical key. */
    public int scanCode() {
        return scanCode;
    }

    public boolean isPhysical() {
        return physical;
    }

    /** Map key used by {@link BoardKeyPressTracker} to pair a key-down with its key-up. */
    public Identity identity() {
        return new Identity(keyCode, scanCode);
    }

    /**
     * Key code plus scancode. Both are needed: a remap rewrites the key code (MULTIFUNCTION as
     * Ctrl), and a ROM can give several keys one key code (MP01 sends KEYCODE_ALT_RIGHT for Sym
     * and emoji, scancodes 249/250). Device and meta state are deliberately excluded.
     */
    public static final class Identity {

        private final int keyCode;
        private final int scanCode;

        private Identity(int keyCode, int scanCode) {
            this.keyCode = keyCode;
            this.scanCode = scanCode;
        }

        public static Identity of(KeyEvent event) {
            return new Identity(event.getKeyCode(), event.getScanCode());
        }

        public int keyCode() {
            return keyCode;
        }

        public int scanCode() {
            return scanCode;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Identity)) {
                return false;
            }
            Identity that = (Identity) other;
            return this.keyCode == that.keyCode && this.scanCode == that.scanCode;
        }

        @Override
        public int hashCode() {
            return 31 * keyCode + scanCode;
        }

        @Override
        public String toString() {
            return "kc=" + keyCode + ",sc=" + scanCode;
        }
    }

    @Nullable
    public ScancodeMapping mapping() {
        return mapping;
    }

    @Nullable
    public KeyRole role() {
        return role;
    }

    public boolean isPseudoEmojiKeyCode() {
        return keyCode == PSEUDO_KEYCODE_EMOJI;
    }

    public boolean isPseudoVoiceKeyCode() {
        return keyCode == PSEUDO_KEYCODE_VOICE;
    }

    public boolean isPseudoBoardKeyCode() {
        return isPseudoEmojiKeyCode() || isPseudoVoiceKeyCode();
    }

    /**
     * A key the IME consumes whole (emoji/voice/sym board or MULTIFUNCTION), by role or
     * pseudo-keycode. Exempt on key-down from board dismissal, cursor-mode disable and the Alt
     * long-press fallthrough, since key-up acts on that state; on key-up its base char must not leak.
     */
    public boolean isBoardKey() {
        return isPseudoBoardKeyCode() || isRoleBoardKey();
    }

    /** The device-config half of {@link #isBoardKey()}, with no pseudo-keycode fallback. */
    public boolean isRoleBoardKey() {
        return role != null && (role.isConsumedAtAccessibilityLevel() || role == KeyRole.MULTIFUNCTION);
    }

    /**
     * BOARD_EMOJI role, the pseudo-keycode, or a MULTIFUNCTION key set to the emoji board (it arms
     * the same {@link PendingKeyAction#EMOJI_BOARD}, so it needs the same key-up path).
     */
    public boolean isEmojiKey() {
        return role == KeyRole.BOARD_EMOJI
                || MultifunctionKeyHandler.ACTION_EMOJI_BOARD.equals(getMultifunctionAction())
                || isPseudoEmojiKeyCode();
    }

    public boolean isVoiceKey() {
        return role == KeyRole.BOARD_VOICE;
    }

    public boolean isMultifunctionKey() {
        return role == KeyRole.MULTIFUNCTION;
    }

    /**
     * A real KEYCODE_SYM (BlackBerry) or a scancode the device config gives the BOARD_SYM role
     * (MP01: KEYCODE_ALT_RIGHT on scancode 249).
     */
    public boolean isSymKey() {
        return isSymBoardKey(keyCode, mapping);
    }

    /** @see #isSymKey() */
    public static boolean isSymBoardKey(int keyCode, @Nullable ScancodeMapping mapping) {
        if (mapping != null && mapping.role != null) {
            // The config's role wins over whatever key code the ROM attached.
            return mapping.role == KeyRole.BOARD_SYM;
        }
        return keyCode == KeyEvent.KEYCODE_SYM;
    }

    public boolean isEnterKey() {
        return keyCode == KeyEvent.KEYCODE_ENTER;
    }

    public boolean isBackspaceKey() {
        return keyCode == KeyEvent.KEYCODE_DEL;
    }

    public boolean isBackKey() {
        return keyCode == KeyEvent.KEYCODE_BACK;
    }

    /** This path always hands FUNCTION back to the framework. */
    public boolean isFunctionKey() {
        return keyCode == KeyEvent.KEYCODE_FUNCTION;
    }

    public boolean isShiftKey() {
        return MetaKeyStateTracker.isShiftKey(keyCode);
    }

    public boolean isAltKey() {
        return MetaKeyStateTracker.isAltKey(keyCode);
    }

    /** Configured action of a MULTIFUNCTION key, else null. Memoised lazily: it reads settings. */
    @Nullable
    public String getMultifunctionAction() {
        if (role != KeyRole.MULTIFUNCTION) {
            return null;
        }
        if (!multifunctionActionResolved) {
            multifunctionAction = MultifunctionKeyHandler.getConfiguredAction(mapping);
            multifunctionActionResolved = true;
        }
        return multifunctionAction;
    }

    /** The config's board id if set, else {@code fallbackBoardId} (a DEFAULT_*_BOARD_ID). */
    public int boardId(int fallbackBoardId) {
        return (mapping != null && mapping.boardId != 0) ? mapping.boardId : fallbackBoardId;
    }

    @Override
    public String toString() {
        return "ResolvedKey{kc=" + keyCode + ", sc=" + scanCode
                + ", physical=" + physical + ", role=" + role + '}';
    }
}
