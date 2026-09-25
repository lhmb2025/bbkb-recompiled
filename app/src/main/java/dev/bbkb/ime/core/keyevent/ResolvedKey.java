package dev.bbkb.ime.core.keyevent;

import android.view.KeyEvent;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import dev.bbkb.ime.core.device.config.model.KeyRole;
import dev.bbkb.ime.core.device.config.model.ScancodeMapping;
import dev.bbkb.ime.core.device.config.resolver.ScancodeMappingResolver;
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier;
import dev.bbkb.ime.core.device.state.MetaKeyStateTracker;

/**
 * One answer to "which key is this", resolved once per {@link KeyEvent} and passed down.
 *
 * <p>Phase 1e of the typing-path consolidation, and deliberately only its first slice: key
 * <b>identity</b>. {@link KeyEventProcessor} used to resolve identity three ways inside one
 * method — the framework key code, the two pseudo-keycodes {@link #PSEUDO_KEYCODE_EMOJI} /
 * {@link #PSEUDO_KEYCODE_VOICE}, and a scancode&rarr;{@link KeyRole} lookup — and it did so more
 * than once per event: the physical-keyboard classification was recomputed after every
 * {@code remapKeyEvent}, and {@code onKeyUpInternal} resolved the same scancode mapping twice.
 * Each of those is a chance for two branches in the same method to disagree about what key the
 * user pressed, which is the bug class this type exists to remove.
 *
 * <h3>What it deliberately does NOT hold</h3>
 * <ul>
 *   <li><b>Modifier state.</b> Ask {@link ModifierState} through
 *       {@code PhysicalKeyboardStateTracker.getModifierState(event)}. Identity is a property of
 *       the key; which modifiers are up or down is not.</li>
 *   <li><b>The emoji / mic / multifunction down-up pairing.</b> That is per-<em>press</em> state,
 *       not per-event: it outlives the event that armed it, by design. Phase 1g gave it an owner
 *       of its own in {@link BoardKeyPressTracker}, keyed by this type's {@link Identity}, and it
 *       does not belong in a value that is built and dropped once per event.</li>
 * </ul>
 *
 * <h3>Instances are per-event and not thread safe</h3>
 * One is built per key event on the key thread, read a handful of times, and dropped. The
 * multifunction action is resolved lazily (it reads settings) so that building a
 * {@code ResolvedKey} costs exactly what the old inline code cost: one device classification and
 * one scancode-mapping lookup.
 */
public final class ResolvedKey {

    /**
     * The emoji key's pseudo-keycode. Not an Android {@code KEYCODE_*}: it is the Minimal Phone's
     * raw {@code .kl} scancode (666), which its ROM also hands through as the key code, so the
     * IME sees a "key code" far outside the framework's range. Treated as the emoji board key
     * wherever it appears, whatever the device config says (see {@link #isEmojiKey()}).
     */
    public static final int PSEUDO_KEYCODE_EMOJI = 666;

    /** The mic/voice key's pseudo-keycode, the same story as {@link #PSEUDO_KEYCODE_EMOJI}. */
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

    /** Lazily resolved; see {@link #getMultifunctionAction()}. */
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
     * Resolve the identity of {@code event}. Call this once per event, on the event the rest of
     * the path will act on — which after a remap is the <em>remapped</em> event, because the
     * remap can change the key code (a MULTIFUNCTION key configured as Ctrl becomes
     * {@code KEYCODE_CTRL_LEFT}) and, in principle, the device it is attributed to.
     */
    public static ResolvedKey of(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        final int scanCode = event.getScanCode();
        return new ResolvedKey(
                keyCode,
                scanCode,
                KeyEventDeviceClassifier.getInstance().isPhysicalKeyboardEvent(event),
                ScancodeMappingResolver.getInstance().resolve(scanCode, keyCode));
    }

    /** The same resolution with both singletons supplied, for tests that drive them directly. */
    @VisibleForTesting
    public static ResolvedKey forTest(int keyCode, int scanCode, boolean physical,
            @Nullable ScancodeMapping mapping) {
        return new ResolvedKey(keyCode, scanCode, physical, mapping);
    }

    // ===================================================================== raw identity

    /** The framework key code, after any remap. */
    public int keyCode() {
        return keyCode;
    }

    /** The raw hardware scancode. This, not the key code, is what identifies a physical key. */
    public int scanCode() {
        return scanCode;
    }

    /** Whether the event came from a physical keyboard. */
    public boolean isPhysical() {
        return physical;
    }

    /**
     * Which physical key this is, as a value that can be compared and used as a map key — what
     * {@link BoardKeyPressTracker} keys a pending action by, so that a key-DOWN and the key-UP
     * that follows it can be recognised as the same press.
     */
    public Identity identity() {
        return new Identity(keyCode, scanCode);
    }

    /**
     * The identity of a physical key: its key code and its scancode, and nothing else.
     *
     * <p>Both halves are needed. The scancode alone is not enough because the same key can arrive
     * with a rewritten key code — a MULTIFUNCTION key configured as Ctrl becomes
     * {@code KEYCODE_CTRL_LEFT} on both its down and its up — and the key code alone is not enough
     * because a ROM can attach one key code to several keys (the MP01 sends
     * {@code KEYCODE_ALT_RIGHT} for both its Sym key and its emoji key, on scancodes 249 and 250).
     * Neither the device nor the meta state is part of it: a key held while the modifiers change is
     * still the same key.
     */
    public static final class Identity {

        private final int keyCode;
        private final int scanCode;

        private Identity(int keyCode, int scanCode) {
            this.keyCode = keyCode;
            this.scanCode = scanCode;
        }

        /** The identity of the key {@code event} belongs to. */
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

    /** The device config's mapping for this key, or {@code null} if none matched. */
    @Nullable
    public ScancodeMapping mapping() {
        return mapping;
    }

    /** The device config's role for this key, or {@code null} if the config is silent. */
    @Nullable
    public KeyRole role() {
        return role;
    }

    // ===================================================================== pseudo-keycodes

    /** This key arrived as the emoji pseudo-keycode 666. */
    public boolean isPseudoEmojiKeyCode() {
        return keyCode == PSEUDO_KEYCODE_EMOJI;
    }

    /** This key arrived as the voice pseudo-keycode 667. */
    public boolean isPseudoVoiceKeyCode() {
        return keyCode == PSEUDO_KEYCODE_VOICE;
    }

    /** Either pseudo-keycode. */
    public boolean isPseudoBoardKeyCode() {
        return isPseudoEmojiKeyCode() || isPseudoVoiceKeyCode();
    }

    // ===================================================================== roles

    /**
     * A key the IME consumes whole: a board key (emoji / voice / sym) or a MULTIFUNCTION key,
     * by device-config role or by pseudo-keycode.
     *
     * <p>Two things hang on this. On the way down it exempts the key from the "an ordinary text
     * key went down" behaviours — board dismissal, cursor-mode disable, the Alt symbol-long-press
     * fallthrough — because its key-UP is what performs the action, and disabling on key-down
     * clears the state that decision reads. On the way up it keeps the key's base character from
     * leaking through the symbol-long-press and extra-key fallthroughs.
     */
    public boolean isBoardKey() {
        return isPseudoBoardKeyCode() || isRoleBoardKey();
    }

    /** The device-config half of {@link #isBoardKey()}, with no pseudo-keycode fallback. */
    public boolean isRoleBoardKey() {
        return role != null && (role.isConsumedAtAccessibilityLevel() || role == KeyRole.MULTIFUNCTION);
    }

    /**
     * The emoji board key: the {@link KeyRole#BOARD_EMOJI} role, a MULTIFUNCTION key whose
     * configured action is the emoji board (its down-event arms the same
     * {@link PendingKeyAction#EMOJI_BOARD}, so it has to take the same key-up path), or the legacy
     * pseudo-keycode.
     */
    public boolean isEmojiKey() {
        return role == KeyRole.BOARD_EMOJI
                || MultifunctionKeyHandler.ACTION_EMOJI_BOARD.equals(getMultifunctionAction())
                || isPseudoEmojiKeyCode();
    }

    /** The voice board key, by device-config role. */
    public boolean isVoiceKey() {
        return role == KeyRole.BOARD_VOICE;
    }

    /** A user-mappable MULTIFUNCTION key. */
    public boolean isMultifunctionKey() {
        return role == KeyRole.MULTIFUNCTION;
    }

    /**
     * Whether this is the Sym key, by either of the two names it answers to: a real
     * {@link KeyEvent#KEYCODE_SYM} (BlackBerry hardware) or a device config that gives its
     * scancode the {@link KeyRole#BOARD_SYM} role (the MP01, whose ROM attaches
     * {@code KEYCODE_ALT_RIGHT} to scancode 249). The scancode is what identifies the key; the
     * key code is just what the {@code .kl} file says.
     */
    public boolean isSymKey() {
        return isSymBoardKey(keyCode, mapping);
    }

    /** @see #isSymKey() */
    public static boolean isSymBoardKey(int keyCode, @Nullable ScancodeMapping mapping) {
        if (mapping != null && mapping.role != null) {
            // The config is authoritative: a key it gives another role to is not the Sym key,
            // whatever key code the ROM attached to it.
            return mapping.role == KeyRole.BOARD_SYM;
        }
        return keyCode == KeyEvent.KEYCODE_SYM;
    }

    // ===================================================================== key-code classes

    /** The Enter / confirm key. */
    public boolean isEnterKey() {
        return keyCode == KeyEvent.KEYCODE_ENTER;
    }

    /** Backspace. */
    public boolean isBackspaceKey() {
        return keyCode == KeyEvent.KEYCODE_DEL;
    }

    /** The navigation BACK key. */
    public boolean isBackKey() {
        return keyCode == KeyEvent.KEYCODE_BACK;
    }

    /** The FUNCTION key, which this path always hands back to the framework. */
    public boolean isFunctionKey() {
        return keyCode == KeyEvent.KEYCODE_FUNCTION;
    }

    /** A physical Shift key, by key code. */
    public boolean isShiftKey() {
        return MetaKeyStateTracker.isShiftKey(keyCode);
    }

    /** A physical Alt key, by key code. */
    public boolean isAltKey() {
        return MetaKeyStateTracker.isAltKey(keyCode);
    }

    // ===================================================================== config payload

    /**
     * The user-configured action for a MULTIFUNCTION key, or {@code null} for any other key.
     *
     * <p>Resolved lazily and memoised: it reads settings, and the key-down path has never needed
     * it, so building a {@code ResolvedKey} must not pay for it.
     */
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

    /**
     * The board this key opens: the device config's own board id when it names one, otherwise
     * {@code fallbackBoardId}.
     *
     * @param fallbackBoardId {@link #DEFAULT_EMOJI_BOARD_ID} or {@link #DEFAULT_VOICE_BOARD_ID}
     */
    public int boardId(int fallbackBoardId) {
        return (mapping != null && mapping.boardId != 0) ? mapping.boardId : fallbackBoardId;
    }

    @Override
    public String toString() {
        return "ResolvedKey{kc=" + keyCode + ", sc=" + scanCode
                + ", physical=" + physical + ", role=" + role + '}';
    }
}
