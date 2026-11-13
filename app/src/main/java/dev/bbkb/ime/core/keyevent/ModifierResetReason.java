package dev.bbkb.ime.core.keyevent;

/**
 * Why the physical modifier state is reset; {@link #scope()} says how much clears. All resets go
 * through {@code PhysicalKeyboardStateTracker.resetModifiers}, the only place that clears and republishes.
 * ALL: spans, AltGr, meta mask, held keys, per-key state, timers, the Alt-used flag; the status icon is
 * re-posted since the system may have dropped it. ALT_ONLY: Alt span and per-key Alt state.
 * MANUAL_SHIFT_ONLY: the Shift span, only while a tapped-and-released Shift is pending.
 */
public enum ModifierResetReason {
    WINDOW_HIDDEN(Scope.ALL),
    FINISH_INPUT(Scope.ALL),
    EDITOR_SWITCHED(Scope.ALL),
    /** Screen off, or another system event invalidated the state. */
    SCREEN_OFF(Scope.ALL),
    /** The rebuilt keyboard dropped the meta mask it installed. */
    KEYBOARD_RELOADED(Scope.ALL),
    /** Full reset from callers that name no reason. */
    UNSPECIFIED(Scope.ALL),

    /** The Alt page was left (Sym pressed, or a gesture switched away). */
    ALT_PAGE_LEFT(Scope.ALT_ONLY),

    /** A commit spent a manual (tapped and released) Shift. */
    MANUAL_SHIFT_SPENT(Scope.MANUAL_SHIFT_ONLY);

    public enum Scope { ALL, ALT_ONLY, MANUAL_SHIFT_ONLY }

    private final Scope scope;

    ModifierResetReason(Scope scope) {
        this.scope = scope;
    }

    public Scope scope() {
        return scope;
    }
}
