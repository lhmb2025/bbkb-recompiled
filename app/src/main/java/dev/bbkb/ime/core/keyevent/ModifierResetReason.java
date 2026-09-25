package dev.bbkb.ime.core.keyevent;

/**
 * Why the physical-keyboard modifier state is being reset, and — through {@link #scope()} — how
 * much of it goes.
 *
 * <p>This is the reset <b>contract</b>. Before Phase 1b the tracker had three public reset
 * entries ({@code resetAllMetaState}, {@code resetAltStateAndNotify}, {@code clearManualShift})
 * plus an internal one, each called from somewhere else, and no statement anywhere of what is
 * supposed to clear when. Every one of them now goes through
 * {@code PhysicalKeyboardStateTracker.resetModifiers(ModifierResetReason)}, which is the only
 * place that clears modifier state and the only place that republishes it.
 *
 * <h3>What clears, and when</h3>
 * <ul>
 *   <li><b>{@link #WINDOW_HIDDEN}, {@link #FINISH_INPUT}, {@link #EDITOR_SWITCHED},
 *       {@link #SCREEN_OFF}, {@link #KEYBOARD_RELOADED}</b> — everything: the Shift/Alt/Sym spans,
 *       AltGr, the keyboard's meta mask (back to identity), the held-key set, the per-key
 *       press/consume state, the long-press and double-tap timers, and the "Alt was used with a
 *       key" flag. The status icon is re-posted unconditionally, because the resulting state is
 *       "nothing set" and the system may have dropped the last post.</li>
 *   <li><b>{@link #ALT_PAGE_LEFT}</b> — the Alt span and the per-key Alt state only. Shift, the
 *       held-key set and the meta mask survive. Used when the Sym key is pressed on a profile
 *       that does not do meta-Sym handling, and when a gesture leaves the Alt page.</li>
 *   <li><b>{@link #MANUAL_SHIFT_SPENT}</b> — the Shift span only, and only while a manual
 *       (tapped, released) Shift is pending. Used after a commit consumed it.</li>
 * </ul>
 *
 * <p>Not a reset, and deliberately not in this enum: {@code consumeModifiersAfterKey}, which
 * <em>spends</em> the pressed modifiers on a key rather than clearing the state, and reports the
 * result through the same single publish point.
 */
public enum ModifierResetReason {

    /** The IME window was hidden. */
    WINDOW_HIDDEN(Scope.ALL),
    /** Input finished on the current editor. */
    FINISH_INPUT(Scope.ALL),
    /** A different editor took the input connection. */
    EDITOR_SWITCHED(Scope.ALL),
    /** The screen went off (or another system event invalidated the modifier state). */
    SCREEN_OFF(Scope.ALL),
    /** The keyboard was rebuilt, so the meta mask it installed is gone. */
    KEYBOARD_RELOADED(Scope.ALL),
    /**
     * A caller outside the key-event package asked for the full reset without naming a reason.
     * Kept so the existing call sites in files this phase does not own keep working unchanged;
     * they should be migrated to a named reason when those files are touched.
     */
    UNSPECIFIED(Scope.ALL),

    /** The Alt page was left (Sym pressed, or a gesture switched away). */
    ALT_PAGE_LEFT(Scope.ALT_ONLY),

    /** A commit spent a manual (tapped and released) Shift. */
    MANUAL_SHIFT_SPENT(Scope.MANUAL_SHIFT_ONLY);

    /** How much of the state a reason clears. */
    public enum Scope { ALL, ALT_ONLY, MANUAL_SHIFT_ONLY }

    private final Scope scope;

    ModifierResetReason(Scope scope) {
        this.scope = scope;
    }

    public Scope scope() {
        return scope;
    }
}
