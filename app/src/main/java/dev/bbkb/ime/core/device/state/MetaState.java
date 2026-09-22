package dev.bbkb.ime.core.device.state;

import android.view.KeyEvent;

/**
 * Value class representing the current meta/modifier key state.
 *
 * <p>The values held here are always real {@link KeyEvent} meta bits — from
 * {@link KeyEvent#getMetaState()} or from a raw int carrying the same encoding.
 *
 * <p>It deliberately does <em>not</em> consume {@link MetaKeyStateTracker}'s span state. That
 * class uses its own layout for the locked bits ({@code META_SHIFT_LOCKED} 0x100,
 * {@code META_ALT_LOCKED} 0x200, {@code META_CAPS_LOCK} 0x800), which is incompatible with
 * {@link KeyEvent#META_CAPS_LOCK_ON} below: a {@code fromSpans(Spannable)} factory used to
 * exist and silently produced a MetaState whose {@code isShiftLocked()} was always false and
 * whose caps-lock span mapped to nothing. Use {@link MetaKeyStateTracker#getMetaState} and its
 * constants for span state.
 *
 * <p>{@code merge}, {@code isFromSpans} and the {@code withX}/{@code withoutX} builders were
 * removed with it; they had no callers.
 */
public class MetaState {

    /** Meta state flags (mirrors KeyEvent meta state constants) */
    public static final int META_SHIFT_ON = KeyEvent.META_SHIFT_ON;           // 0x1
    public static final int META_ALT_ON = KeyEvent.META_ALT_ON;               // 0x2
    public static final int META_CTRL_ON = KeyEvent.META_CTRL_ON;             // 0x1000
    public static final int META_SYM_ON = KeyEvent.META_SYM_ON;               // 0x4
    public static final int META_SHIFT_LOCKED = KeyEvent.META_CAPS_LOCK_ON;   // 0x100000

    private final int metaState;

    private MetaState(int metaState) {
        this.metaState = metaState;
    }

    /**
     * Create MetaState from a KeyEvent's meta state.
     */
    public static MetaState fromKeyEvent(KeyEvent event) {
        if (event == null) {
            return new MetaState(0);
        }
        return new MetaState(event.getMetaState());
    }

    /**
     * Create MetaState from a raw meta state integer.
     */
    public static MetaState fromRaw(int metaState) {
        return new MetaState(metaState);
    }

    /**
     * Get the raw meta state integer.
     */
    public int getRawMetaState() {
        return metaState;
    }

    /**
     * Check if shift is active (pressed or locked).
     */
    public boolean isShiftActive() {
        return (metaState & (META_SHIFT_ON | META_SHIFT_LOCKED)) != 0;
    }

    /**
     * Check if shift is just pressed (not locked).
     */
    public boolean isShiftPressed() {
        return (metaState & META_SHIFT_ON) != 0 && !isShiftLocked();
    }

    /**
     * Check if shift is locked (caps lock).
     */
    public boolean isShiftLocked() {
        return (metaState & META_SHIFT_LOCKED) != 0;
    }

    /**
     * Check if alt is active.
     *
     * <p>There is no alt-locked bit in the KeyEvent encoding — alt lock is a span-tracker
     * concept ({@code MetaKeyStateTracker.META_ALT_LOCKED}) that never reached this class.
     */
    public boolean isAltActive() {
        return (metaState & META_ALT_ON) != 0;
    }

    /**
     * Check if alt is pressed. Equivalent to {@link #isAltActive()}; kept for call-site clarity.
     */
    public boolean isAltPressed() {
        return isAltActive();
    }

    /**
     * Check if ctrl is active.
     */
    public boolean isCtrlActive() {
        return (metaState & META_CTRL_ON) != 0;
    }

    /**
     * Check if sym is active.
     */
    public boolean isSymActive() {
        return (metaState & META_SYM_ON) != 0;
    }

    /**
     * Check if any modifier is active.
     */
    public boolean hasAnyModifier() {
        return isShiftActive() || isAltActive() || isCtrlActive() || isSymActive();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MetaState metaState1 = (MetaState) o;
        return metaState == metaState1.metaState;
    }

    @Override
    public int hashCode() {
        return metaState;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("MetaState{");
        if (isShiftLocked()) sb.append("CAPS_LOCK ");
        else if (isShiftPressed()) sb.append("SHIFT ");
        if (isAltActive()) sb.append("ALT ");
        if (isCtrlActive()) sb.append("CTRL ");
        if (isSymActive()) sb.append("SYM ");
        sb.append("raw=0x").append(Integer.toHexString(metaState));
        sb.append("}");
        return sb.toString();
    }
}
