package dev.bbkb.ime.core.keyevent;

import android.view.KeyEvent;

/**
 * Immutable snapshot of which modifiers are active, and how. Three meta states, not interchangeable:
 * <ul>
 *   <li>{@link #getModifierKeyMetaState()}: only real modifier key presses. Use for chords.</li>
 *   <li>{@link #getInterpretedMetaState()}: plus AltGr, through the keyboard's MetaMask (the PKB
 *       symbol board's Alt page adds META_ALT_ON). For characters only, never chords.</li>
 *   <li>{@link #getEventMetaState()}: what the KeyEvent carried (held Alt, not sticky/locked).</li>
 * </ul>
 * Pressed span bits reuse KeyEvent values (0x1 shift, 0x2 alt); locked bits are the span
 * tracker's own (0x100/0x200/0x400/0x800), distinct from {@link KeyEvent#META_CAPS_LOCK_ON}.
 * Per-key Shift/Alt state is DOWN, RELEASED or CONSUMED (still down but spent by a chord, another
 * held key, or a typed character); a consumed Shift is neither held nor released.
 */
public final class ModifierState {
    // Span-encoding locked bits (MetaKeyStateTracker's META_*_LOCKED), not KeyEvent values.
    public static final int SPAN_SHIFT_LOCKED = 0x100;
    public static final int SPAN_ALT_LOCKED = 0x200;
    /** {@code MetaKeyStateTracker.META_CTRL_LOCKED}, which is really the Sym (key 63) span. */
    public static final int SPAN_SYM_LOCKED = 0x400;
    public static final int SPAN_CAPS_LOCK = 0x800;

    /** Every bit that can mean Alt, including the span alt-lock. */
    public static final int ALT_ANY_MASK = KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON
            | KeyEvent.META_ALT_RIGHT_ON | SPAN_ALT_LOCKED;

    /** Every bit that can mean Shift, in the span encoding. */
    public static final int SHIFT_ANY_MASK = KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON
            | KeyEvent.META_SHIFT_RIGHT_ON | SPAN_SHIFT_LOCKED | SPAN_CAPS_LOCK;

    /**
     * Every bit that can mean Sym. The tracker's misnamed CTRL_SPAN is the key-63 (Sym) span and
     * sets META_SYM_ON; real Ctrl is not span state (see {@link #isCtrlActive()}).
     */
    public static final int SYM_ANY_MASK = KeyEvent.META_SYM_ON | SPAN_SYM_LOCKED;

    private final int eventMeta;
    private final int modifierKeyMeta;
    private final int interpretedMeta;
    private final boolean altGr;
    private final boolean shiftKeyDown;
    private final boolean shiftKeyConsumed;
    private final boolean altKeyDown;
    private final boolean altSpanHeld;
    private final boolean altUsedWithKey;
    private final boolean symKeyHeld;
    private final boolean ctrlActive;
    private final boolean ctrlLocked;
    private final boolean shiftSurvivesMask;
    private final int keysHeld;
    private final boolean shiftKeyPhysicallyDown;
    private final boolean altKeyPhysicallyDown;

    private ModifierState(Builder b) {
        this.eventMeta = b.eventMeta;
        this.modifierKeyMeta = b.modifierKeyMeta;
        this.interpretedMeta = b.interpretedMeta;
        this.altGr = b.altGr;
        this.shiftKeyDown = b.shiftKeyDown;
        this.shiftKeyConsumed = b.shiftKeyConsumed;
        this.altKeyDown = b.altKeyDown;
        this.altSpanHeld = b.altSpanHeld;
        this.altUsedWithKey = b.altUsedWithKey;
        this.symKeyHeld = b.symKeyHeld;
        this.ctrlActive = b.ctrlActive;
        this.ctrlLocked = b.ctrlLocked;
        this.shiftSurvivesMask = b.shiftSurvivesMask;
        this.keysHeld = b.keysHeld;
        this.shiftKeyPhysicallyDown = b.shiftKeyPhysicallyDown;
        this.altKeyPhysicallyDown = b.altKeyPhysicallyDown;
    }

    /** From the event alone, with no tracker. */
    public static ModifierState ofEvent(KeyEvent event) {
        return ofEventMeta(event == null ? 0 : event.getMetaState());
    }

    public static ModifierState ofEventMeta(int eventMetaState) {
        return new Builder().eventMeta(eventMetaState).build();
    }

    public static ModifierState none() {
        return ofEventMeta(0);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Span state from real modifier key presses, no AltGr or mask. Judge chords against this. */
    public int getModifierKeyMetaState() {
        return modifierKeyMeta;
    }

    /** Span state plus AltGr through the keyboard's MetaMask; never use for chord detection. */
    public int getInterpretedMetaState() {
        return interpretedMeta;
    }

    public int getEventMetaState() {
        return eventMeta;
    }

    /**
     * Event meta OR modifier-key span state. Held Alt is in the first, sticky/locked Alt only in
     * the second (and so is held Alt when the accessibility service eats hardware Alt).
     */
    public int getChordMetaState() {
        return eventMeta | modifierKeyMeta;
    }

    /** A Shift key is DOWN and not spent (a CONSUMED one is not held), or the event says Shift. */
    public boolean isShiftHeld() {
        return shiftKeyDown || (eventMeta & KeyEvent.META_SHIFT_ON) != 0;
    }

    /**
     * Tapped and waiting for the next character: span on, not locked, no Shift key down in any
     * state (a Shift still held after a chord is not sticky until released).
     */
    public boolean isShiftSticky() {
        return isShiftReleased() && !isShiftLocked()
                && (modifierKeyMeta & KeyEvent.META_SHIFT_ON) != 0;
    }

    /** Double tap or caps lock, in either encoding. */
    public boolean isShiftLocked() {
        return (modifierKeyMeta & (SPAN_SHIFT_LOCKED | SPAN_CAPS_LOCK)) != 0 || isEventCapsLockOn();
    }

    /** Held, sticky, locked, or on in the span while a consumed Shift is held (Shift+Alt). */
    public boolean isShiftActive() {
        return isShiftHeld() || isShiftLocked() || (modifierKeyMeta & KeyEvent.META_SHIFT_ON) != 0;
    }

    public boolean isShiftConsumed() {
        return shiftKeyConsumed;
    }

    /** No Shift key is down or down-but-consumed. Per-key state only; ignores event meta. */
    public boolean isShiftReleased() {
        return !shiftKeyDown && !shiftKeyConsumed;
    }

    /** Set by a modifier key and not masked off by the MetaMask; picks the shifted symbol page. */
    public boolean isShiftActiveForLayout() {
        return (modifierKeyMeta & KeyEvent.META_SHIFT_ON) != 0 && shiftSurvivesMask;
    }

    public boolean isShiftLockedForLayout() {
        return (interpretedMeta & SPAN_SHIFT_LOCKED) != 0;
    }

    /** KeyEvent encoding (0x100000), unrelated to the span's 0x100. */
    public boolean isEventCapsLockOn() {
        return (eventMeta & KeyEvent.META_CAPS_LOCK_ON) != 0;
    }

    /**
     * Tapped Shift since released: on for the layout and every Shift key RELEASED. Must match
     * PhysicalKeyboardStateTracker.isManualShiftAndShiftPressing() (pinned by ShiftLifecycleTest).
     */
    public boolean isManualShiftPending() {
        return isShiftActiveForLayout() && isShiftReleased();
    }

    /**
     * The Alt span is PRESSED. Narrower than {@link #isAltHeld()}: pressing a sticky Alt clears the
     * span but marks the key down, and the accessibility chord decision reads the span alone.
     */
    public boolean isAltHeldBySpan() {
        return altSpanHeld;
    }

    public boolean isAltKeyDown() {
        return altKeyDown;
    }

    /** By span, per-key state or event meta. */
    public boolean isAltHeld() {
        return altSpanHeld || altKeyDown || (eventMeta & KeyEvent.META_ALT_ON) != 0;
    }

    public boolean isAltSticky() {
        return !isAltHeld() && !isAltLocked() && (modifierKeyMeta & KeyEvent.META_ALT_ON) != 0;
    }

    public boolean isAltLocked() {
        return (modifierKeyMeta & SPAN_ALT_LOCKED) != 0;
    }

    public boolean isAltActive() {
        return isAltHeld() || isAltSticky() || isAltLocked();
    }

    /** For chords: event meta and modifier keys only, blind to the symbol board's Alt page. */
    public boolean isAltActiveForChord() {
        return (getChordMetaState() & ALT_ANY_MASK) != 0;
    }

    /** For characters: event OR interpreted meta, so the symbol board's Alt page counts. */
    public boolean isAltActiveForCharacter() {
        return ((eventMeta | interpretedMeta) & ALT_ANY_MASK) != 0;
    }

    /** AltGr is the Shift+Alt composite. */
    public boolean isAltGrActive() {
        return altGr;
    }

    public boolean isAltUsedWithKey() {
        return altUsedWithKey;
    }

    /** From the tracker's held-key set; keeps the PKB symbol board open for several symbols. */
    public boolean isSymHeld() {
        return symKeyHeld || (eventMeta & KeyEvent.META_SYM_ON) != 0;
    }

    public boolean isSymLocked() {
        return (modifierKeyMeta & SPAN_SYM_LOCKED) != 0;
    }

    public boolean isSymActive() {
        return isSymHeld() || isSymLocked()
                || (modifierKeyMeta & KeyEvent.META_SYM_ON) != 0;
    }

    /**
     * A Ctrl key is down, sticky Ctrl is latched, or the event has META_CTRL_ON. Ctrl's only owner
     * is ControlModeController (not span state); the tracker reads it through a supplier.
     */
    public boolean isCtrlActive() {
        return ctrlActive || (eventMeta & KeyEvent.META_CTRL_ON) != 0;
    }

    public boolean isCtrlLocked() {
        return ctrlLocked;
    }

    public boolean hasAnyModifier() {
        return isShiftActive() || isAltActive() || isCtrlActive() || isSymActive();
    }

    public int getKeysHeldCount() {
        return keysHeld;
    }

    /** {@code FccController.showFcc()}'s extra precondition. */
    public boolean isAnyKeyHeld() {
        return keysHeld > 0;
    }

    /**
     * A Shift key is down in any state, even spent or mid double tap (unlike {@link #isShiftHeld()}).
     * Display only (status-bar icon); never decides typing.
     */
    public boolean isShiftKeyPhysicallyDown() {
        return shiftKeyPhysicallyDown;
    }

    public boolean isAltKeyPhysicallyDown() {
        return altKeyPhysicallyDown;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ModifierState{");
        if (isShiftLocked()) sb.append("SHIFT_LOCKED ");
        else if (isShiftHeld()) sb.append("SHIFT_HELD ");
        else if (shiftKeyConsumed) sb.append("SHIFT_CONSUMED ");
        else if (isShiftSticky()) sb.append("SHIFT_STICKY ");
        if (isAltLocked()) sb.append("ALT_LOCKED ");
        else if (isAltHeld()) sb.append("ALT_HELD ");
        else if (isAltSticky()) sb.append("ALT_STICKY ");
        if (isCtrlActive()) sb.append("CTRL ");
        if (isSymActive()) sb.append("SYM ");
        if (altGr) sb.append("ALTGR ");
        sb.append("keys=").append(modifierKeyMeta == 0 ? "0" : "0x" + Integer.toHexString(modifierKeyMeta));
        sb.append(" interp=").append(interpretedMeta == 0 ? "0" : "0x" + Integer.toHexString(interpretedMeta));
        sb.append(" event=").append(eventMeta == 0 ? "0" : "0x" + Integer.toHexString(eventMeta));
        return sb.append('}').toString();
    }

    /** Only PhysicalKeyboardStateTracker fills in more than the event meta. */
    public static final class Builder {
        private int eventMeta;
        private int modifierKeyMeta;
        private int interpretedMeta;
        private boolean altGr;
        private boolean shiftKeyDown;
        private boolean shiftKeyConsumed;
        private boolean altKeyDown;
        private boolean altSpanHeld;
        private boolean altUsedWithKey;
        private boolean symKeyHeld;
        private boolean ctrlActive;
        private boolean ctrlLocked;
        private boolean shiftSurvivesMask = true;
        private int keysHeld;
        private boolean shiftKeyPhysicallyDown;
        private boolean altKeyPhysicallyDown;

        public Builder event(KeyEvent event) {
            return eventMeta(event == null ? 0 : event.getMetaState());
        }

        public Builder eventMeta(int value) {
            this.eventMeta = value;
            return this;
        }

        public Builder modifierKeyMeta(int value) {
            this.modifierKeyMeta = value;
            return this;
        }

        public Builder interpretedMeta(int value) {
            this.interpretedMeta = value;
            return this;
        }

        public Builder altGr(boolean value) {
            this.altGr = value;
            return this;
        }

        public Builder shiftKeyDown(boolean value) {
            this.shiftKeyDown = value;
            return this;
        }

        public Builder shiftKeyConsumed(boolean value) {
            this.shiftKeyConsumed = value;
            return this;
        }

        public Builder altKeyDown(boolean value) {
            this.altKeyDown = value;
            return this;
        }

        public Builder altSpanHeld(boolean value) {
            this.altSpanHeld = value;
            return this;
        }

        public Builder altUsedWithKey(boolean value) {
            this.altUsedWithKey = value;
            return this;
        }

        public Builder symKeyHeld(boolean value) {
            this.symKeyHeld = value;
            return this;
        }

        public Builder ctrlActive(boolean value) {
            this.ctrlActive = value;
            return this;
        }

        public Builder ctrlLocked(boolean value) {
            this.ctrlLocked = value;
            return this;
        }

        /** Whether META_SHIFT_ON survives the keyboard's meta mask. */
        public Builder shiftSurvivesMask(boolean value) {
            this.shiftSurvivesMask = value;
            return this;
        }

        public Builder keysHeld(int value) {
            this.keysHeld = value;
            return this;
        }

        public Builder shiftKeyPhysicallyDown(boolean value) {
            this.shiftKeyPhysicallyDown = value;
            return this;
        }

        public Builder altKeyPhysicallyDown(boolean value) {
            this.altKeyPhysicallyDown = value;
            return this;
        }

        public ModifierState build() {
            return new ModifierState(this);
        }
    }
}
