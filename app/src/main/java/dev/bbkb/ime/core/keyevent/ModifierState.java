package dev.bbkb.ime.core.keyevent;

import android.view.KeyEvent;

/**
 * One immutable answer to "which modifiers are active right now, and in what way".
 *
 * <p>Phase 1b of the state/typing consolidation. Before this class the physical-keyboard path
 * asked that question four different ways — integer masks pulled off the span tracker, boolean
 * predicates over the tracker's per-key arrays, raw {@link KeyEvent} meta bits at the call site,
 * and lookups in the held-key set — and the four disagreed in ways that produced real bugs (the
 * Sym&rarr;emoji regression of 2026-09-21, the status icon that outlived its modifier).
 *
 * <h3>The three meta states, which are NOT the same thing</h3>
 * <ul>
 *   <li>{@link #getModifierKeyMetaState()} — <b>the modifier KEYS' own state</b>: held, sticky or
 *       locked Shift/Alt/Sym established by the user pressing real modifier keys, and nothing
 *       else. This is {@code PhysicalKeyboardStateTracker.getModifierKeyMetaState()}. Chord
 *       detection (Alt+Sym) must use this one.</li>
 *   <li>{@link #getInterpretedMetaState()} — <b>what a character key should be interpreted
 *       against</b>: the above plus AltGr, run through the current keyboard's
 *       {@link KeyCharacterInterpreter.MetaMask}. On a PKB the symbol board's Alt page <em>is</em>
 *       such a mask: it adds {@link KeyEvent#META_ALT_ON} so letter keys type their Alt-layer
 *       symbols. That is right for typing and wrong for "is the user holding Alt?" — reading it
 *       for the Alt+Sym chord made paging with Sym open the emoji board.</li>
 *   <li>{@link #getEventMetaState()} — <b>what the system says</b>: the meta state the hardware
 *       {@link KeyEvent} carried. A physically held Alt lives here; a tapped (sticky) or
 *       double-tapped (locked) one does not, and on a device whose accessibility service eats
 *       hardware Alt the held Alt is only in the span state. Neither source alone is enough,
 *       which is what {@link #getChordMetaState()} exists for.</li>
 * </ul>
 *
 * <h3>Two bit encodings, also not the same thing</h3>
 * The <em>pressed</em> bits of the span state deliberately reuse the {@link KeyEvent} values
 * ({@code META_SHIFT_ON} 0x1, {@code META_ALT_ON} 0x2), but the <em>locked</em> bits are the span
 * tracker's own ({@code 0x100} shift-locked, {@code 0x200} alt-locked, {@code 0x400} sym-locked,
 * {@code 0x800} caps-lock), and they collide with nothing in {@code KeyEvent}. An event's own
 * caps-lock bit is {@link KeyEvent#META_CAPS_LOCK_ON} (0x100000) instead — see
 * {@link #isEventCapsLockOn()}. Mixing the two silently produced a state whose "shift locked" was
 * always false, which is why {@code MetaState.fromSpans} was deleted.
 *
 * <h3>Per-key state, which is none of the three</h3>
 * Besides the meta states, the tracker keeps a per-key state for each physical Shift and Alt key:
 * DOWN, RELEASED or CONSUMED. CONSUMED means the key is still physically down but has already
 * been spent: something else happened while it was held (Alt or the other Shift was pressed,
 * another key was already down when it was pressed, or a character was typed with it). The
 * snapshot carries it as {@code shiftKeyDown} / {@code shiftKeyConsumed}, and the Shift queries
 * that read it say so. A consumed Shift is neither {@link #isShiftHeld() held} nor
 * {@link #isShiftReleased() released}; ask {@link #isShiftConsumed()}. Phase 1h added it: without
 * it the snapshot called a consumed Shift "released", which the tracker never did.
 *
 * <p>Instances are cheap value objects; build one per question, not one per process.
 */
public final class ModifierState {

    // ---- span-state locked bits (MetaKeyStateTracker's own encoding, NOT KeyEvent values) ----

    /** Shift is locked (caps). Span encoding; {@code MetaKeyStateTracker.META_SHIFT_LOCKED}. */
    public static final int SPAN_SHIFT_LOCKED = 0x100;
    /** Alt is locked. Span encoding; {@code MetaKeyStateTracker.META_ALT_LOCKED}. */
    public static final int SPAN_ALT_LOCKED = 0x200;
    /** The Sym/key-63 span is locked. Span encoding; {@code MetaKeyStateTracker.META_CTRL_LOCKED}. */
    public static final int SPAN_SYM_LOCKED = 0x400;
    /** The caps-lock span. Span encoding; {@code MetaKeyStateTracker.META_CAPS_LOCK}. */
    public static final int SPAN_CAPS_LOCK = 0x800;

    /**
     * Every bit that can mean "Alt", including the span tracker's alt-lock bit. The left/right
     * bits are in here because a caller that ORs a raw event meta into a span meta can carry
     * them; the framework never sets one without {@link KeyEvent#META_ALT_ON} (KeyEvent
     * normalizes its meta state), so they only ever widen the answer in theory.
     */
    public static final int ALT_ANY_MASK = KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON
            | KeyEvent.META_ALT_RIGHT_ON | SPAN_ALT_LOCKED;

    /** Every bit that can mean "Shift", in the span encoding. */
    public static final int SHIFT_ANY_MASK = KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON
            | KeyEvent.META_SHIFT_RIGHT_ON | SPAN_SHIFT_LOCKED | SPAN_CAPS_LOCK;

    /**
     * Every bit that can mean "Sym". {@link KeyEvent#META_SYM_ON} is also what the span tracker
     * writes for its key-63 span — the span it calls CTRL_SPAN, which is a misnomer: key code 63
     * is {@link KeyEvent#KEYCODE_SYM}, and the flag it sets is META_SYM_ON, not META_CTRL_ON.
     * Real Ctrl is not span state at all (see {@link #isCtrlActive()}).
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
    private final boolean shiftSurvivesMask;
    private final int keysHeld;

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
        this.shiftSurvivesMask = b.shiftSurvivesMask;
        this.keysHeld = b.keysHeld;
    }

    // ===================================================================== factories

    /** The state a bare {@link KeyEvent} reports, with no tracker in the picture. */
    public static ModifierState ofEvent(KeyEvent event) {
        return ofEventMeta(event == null ? 0 : event.getMetaState());
    }

    /** The state a raw meta-state integer reports, with no tracker in the picture. */
    public static ModifierState ofEventMeta(int eventMetaState) {
        return new Builder().eventMeta(eventMetaState).build();
    }

    /** Nothing is active. */
    public static ModifierState none() {
        return ofEventMeta(0);
    }

    public static Builder builder() {
        return new Builder();
    }

    // ===================================================================== raw meta states

    /**
     * The modifier KEYS' span state: held, sticky or locked Shift/Alt/Sym from real key presses,
     * with neither AltGr nor the keyboard's meta mask applied. The one to judge a chord against.
     */
    public int getModifierKeyMetaState() {
        return modifierKeyMeta;
    }

    /**
     * The state a character key should be interpreted against: the span state plus AltGr, run
     * through the current keyboard's {@link KeyCharacterInterpreter.MetaMask}. Carries the symbol
     * board's Alt page as {@code META_ALT_ON}, so it must never be used for chord detection.
     */
    public int getInterpretedMetaState() {
        return interpretedMeta;
    }

    /** The meta state the hardware {@link KeyEvent} itself carried, or 0 if there was no event. */
    public int getEventMetaState() {
        return eventMeta;
    }

    /**
     * The state a chord (Alt+Sym) is judged against: the system's meta OR the modifier keys' own
     * span state. Held Alt arrives in the first, sticky/locked Alt only in the second, and on a
     * device whose accessibility service consumes hardware Alt a held Alt is only in the second
     * as well — so neither half can be dropped.
     */
    public int getChordMetaState() {
        return eventMeta | modifierKeyMeta;
    }

    // ===================================================================== Shift

    /**
     * A physical Shift key is DOWN and not yet spent (per-key state), or the event says Shift is
     * on. {@code PhysicalKeyboardStateTracker.isShiftKeyDown()} when there is no event.
     *
     * <p>A Shift key that is physically down but CONSUMED is <em>not</em> held in this sense;
     * see {@link #isShiftConsumed()}.
     */
    public boolean isShiftHeld() {
        return shiftKeyDown || (eventMeta & KeyEvent.META_SHIFT_ON) != 0;
    }

    /**
     * Shift was tapped and is waiting to be spent on the next character: the span says Shift is
     * on, it is not locked, and no Shift key is physically down in any state. A Shift the user is
     * still holding after chording it with Alt also leaves the span on, but it is not sticky
     * until it is let go.
     */
    public boolean isShiftSticky() {
        return isShiftReleased() && !isShiftLocked()
                && (modifierKeyMeta & KeyEvent.META_SHIFT_ON) != 0;
    }

    /** Shift is locked (double tap, or caps lock), in either encoding. */
    public boolean isShiftLocked() {
        return (modifierKeyMeta & (SPAN_SHIFT_LOCKED | SPAN_CAPS_LOCK)) != 0 || isEventCapsLockOn();
    }

    /**
     * Shift is on in any way: held, sticky, locked, or still on in the span while a consumed Shift
     * key is held (Shift+Alt).
     */
    public boolean isShiftActive() {
        return isShiftHeld() || isShiftLocked() || (modifierKeyMeta & KeyEvent.META_SHIFT_ON) != 0;
    }

    /**
     * A Shift key is physically down but already spent: it was chorded with Alt or the other
     * Shift, pressed while another key was down, or a character was typed with it. Per-key
     * state; {@code PhysicalKeyboardStateTracker.isShiftKeyConsumed()}.
     */
    public boolean isShiftConsumed() {
        return shiftKeyConsumed;
    }

    /**
     * Every Shift key is RELEASED: none is down, and none is down-but-consumed. Per-key state
     * only; the event meta is not consulted. {@code PhysicalKeyboardStateTracker.isShiftReleased()}.
     */
    public boolean isShiftReleased() {
        return !shiftKeyDown && !shiftKeyConsumed;
    }

    /**
     * Shift as the <em>keyboard layout</em> sees it: set by a modifier key AND not masked off by
     * the current keyboard's meta mask. This is {@code PhysicalKeyboardStateTracker.hasMetaFlag(
     * META_SHIFT_ON)}, and it is what picks the shifted symbol page.
     */
    public boolean isShiftActiveForLayout() {
        return (modifierKeyMeta & KeyEvent.META_SHIFT_ON) != 0 && shiftSurvivesMask;
    }

    /** Shift-lock as the interpreted state reports it (span encoding, 0x100). */
    public boolean isShiftLockedForLayout() {
        return (interpretedMeta & SPAN_SHIFT_LOCKED) != 0;
    }

    /**
     * The event's own caps-lock bit, {@link KeyEvent#META_CAPS_LOCK_ON} (0x100000) — the
     * <em>KeyEvent</em> encoding, which has nothing to do with the span tracker's 0x100.
     */
    public boolean isEventCapsLockOn() {
        return (eventMeta & KeyEvent.META_CAPS_LOCK_ON) != 0;
    }

    /**
     * A Shift the user set with a tap and has since let go of — "manual shift": Shift is on for
     * the layout and every Shift key is RELEASED. A Shift still held after chording it with Alt
     * is not pending (it is CONSUMED, not released). Same answer as
     * {@code PhysicalKeyboardStateTracker.isManualShiftAndShiftPressing()} in every state;
     * {@code ShiftLifecycleTest} pins that.
     */
    public boolean isManualShiftPending() {
        return isShiftActiveForLayout() && isShiftReleased();
    }

    // ===================================================================== Alt

    /**
     * The Alt <em>span</em> is in its PRESSED state. Narrower than {@link #isAltHeld()} on
     * purpose: pressing Alt while it was already sticky clears the span but still marks the key
     * down, and the all-keys accessibility callback has always keyed its "a key was chorded with
     * a held Alt" decision on the span alone.
     */
    public boolean isAltHeldBySpan() {
        return altSpanHeld;
    }

    /** A physical Alt key is down per the tracker's per-key state. */
    public boolean isAltKeyDown() {
        return altKeyDown;
    }

    /** A physical Alt key is down, by any of the three witnesses. */
    public boolean isAltHeld() {
        return altSpanHeld || altKeyDown || (eventMeta & KeyEvent.META_ALT_ON) != 0;
    }

    /** Alt was tapped and is waiting to be spent on the next character. */
    public boolean isAltSticky() {
        return !isAltHeld() && !isAltLocked() && (modifierKeyMeta & KeyEvent.META_ALT_ON) != 0;
    }

    /** Alt is locked (double tap). Span encoding, 0x200. */
    public boolean isAltLocked() {
        return (modifierKeyMeta & SPAN_ALT_LOCKED) != 0;
    }

    /** Alt is on in any way: held, sticky or locked. */
    public boolean isAltActive() {
        return isAltHeld() || isAltSticky() || isAltLocked();
    }

    /**
     * Alt for <b>chord detection</b>: held, sticky or locked, from the system meta and the
     * modifier KEYS only. Deliberately blind to the symbol board's Alt page.
     */
    public boolean isAltActiveForChord() {
        return (getChordMetaState() & ALT_ANY_MASK) != 0;
    }

    /**
     * Alt for <b>character interpretation</b>: the system meta OR the interpreted state, so the
     * symbol board's Alt page counts. This is the question "should this key produce its Alt
     * character", not "is the user holding Alt".
     */
    public boolean isAltActiveForCharacter() {
        return ((eventMeta | interpretedMeta) & ALT_ANY_MASK) != 0;
    }

    /** AltGr (the Shift+Alt composite) is engaged. */
    public boolean isAltGrActive() {
        return altGr;
    }

    /** An Alt that has already been spent on a key. {@code isAltUsedWithKey()}. */
    public boolean isAltUsedWithKey() {
        return altUsedWithKey;
    }

    // ===================================================================== Sym

    /**
     * The Sym key is physically held down — from the tracker's held-key set, which is what keeps
     * the PKB symbol board open for more than one symbol.
     */
    public boolean isSymHeld() {
        return symKeyHeld || (eventMeta & KeyEvent.META_SYM_ON) != 0;
    }

    /** The Sym span is locked. Span encoding, 0x400. */
    public boolean isSymLocked() {
        return (modifierKeyMeta & SPAN_SYM_LOCKED) != 0;
    }

    /** Sym is on in any way. */
    public boolean isSymActive() {
        return isSymHeld() || isSymLocked()
                || (modifierKeyMeta & KeyEvent.META_SYM_ON) != 0;
    }

    // ===================================================================== Ctrl

    /**
     * Ctrl is active: a physical Ctrl key is down, the sticky Ctrl mode is latched, or the event
     * carries {@link KeyEvent#META_CTRL_ON}.
     *
     * <p>Ctrl has exactly one owner, {@code ControlModeController} — it is not span state, and the
     * span tracker's "CTRL_SPAN" is really the Sym key (see {@link #SYM_ANY_MASK}). The tracker
     * asks the controller through a supplier rather than keeping a copy.
     */
    public boolean isCtrlActive() {
        return ctrlActive || (eventMeta & KeyEvent.META_CTRL_ON) != 0;
    }

    // ===================================================================== misc

    /** Any modifier at all. */
    public boolean hasAnyModifier() {
        return isShiftActive() || isAltActive() || isCtrlActive() || isSymActive();
    }

    /** How many physical keys the tracker counts as held. */
    public int getKeysHeldCount() {
        return keysHeld;
    }

    /** Whether any physical key is held — {@code FccController.showFcc()}'s extra precondition. */
    public boolean isAnyKeyHeld() {
        return keysHeld > 0;
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

    // ===================================================================== builder

    /**
     * Assembles a snapshot. Only {@code PhysicalKeyboardStateTracker} fills in more than the
     * event meta; everyone else asks it for a finished {@link ModifierState}.
     */
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
        private boolean shiftSurvivesMask = true;
        private int keysHeld;

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

        /**
         * A Shift key is down but CONSUMED (per-key state). Only the tracker knows this; a
         * snapshot built from an event alone leaves it false.
         */
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

        /**
         * Whether {@link KeyEvent#META_SHIFT_ON} survives the current keyboard's meta mask —
         * the second half of the tracker's {@code hasMetaFlag(META_SHIFT_ON)}.
         */
        public Builder shiftSurvivesMask(boolean value) {
            this.shiftSurvivesMask = value;
            return this;
        }

        public Builder keysHeld(int value) {
            this.keysHeld = value;
            return this;
        }

        public ModifierState build() {
            return new ModifierState(this);
        }
    }
}
