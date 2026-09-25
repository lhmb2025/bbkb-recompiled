package dev.bbkb.ime.keyboard.state;

/**
 * The one funnel: {@link #apply(KeyboardTransition)} takes a declarative change and applies it to
 * all four axes, in the order {@link CrossAxisRules} prescribes.
 *
 * <p>Modelled on {@code UnifiedBoardCoordinator}, which has owned the board axis alone since July
 * 2026 and proved the shape: pure decision logic here, every side effect behind a small
 * {@link Axes} interface, so the whole thing runs on the JVM against a fake. The production
 * {@code Axes} lives in {@code KeyboardSwitcher}; it does nothing but call the same mechanism
 * methods the scattered sweeps used to call, in the same order.
 *
 * <p><b>What moved and what did not.</b> The mechanism — how a board hides, how the bar is raised,
 * how a keyboard is built — is unchanged and still lives where it lived. What moved is the
 * <em>decision</em>: which axes a change touches, in what order, and with what exemptions. Callers
 * name the change; this class and the table answer the rest.
 *
 * <h2>The one composite effect left</h2>
 * {@link CrossAxisRules.BoardEffect#OPEN_EXCLUSIVE} and {@link CrossAxisRules.BoardEffect#CLOSE}
 * are applied as a single mechanism call rather than one call per column:
 * {@code UnifiedInputBoardManager.openBoard} / {@code closeBoard} already perform the layout clear
 * and the bar restore inside the same tested unit, and the bar restore needs a snapshot taken
 * before the close.
 *
 * <p>{@code ENTER_CURSOR_MODE} used to be composite too, for the reason Phase 1a recorded:
 * {@code showArrowBar} closed the boards <em>twice</em>, so its board and bar columns alternated.
 * Owner ruling R4 deleted the first (inverted, no-op-while-the-UIM-is-enabled) close, and
 * {@link #enterCursorModeSurfaces} now applies each column as its own call.
 */
public final class KeyboardStateCoordinator {

    /**
     * The four axes' mechanisms, as the funnel needs them. Every method is an existing production
     * call under a name that says which axis it belongs to.
     */
    public interface Axes {

        // ── layout ────────────────────────────────────────────────────────

        /**
         * Leave EMOJI mode without UI callbacks, take the palettes down and restore the main
         * keyboard view — the layout column of {@code BOARD_LOWERED(-11)}.
         *
         * <p>Owner ruling R1: this returns to ALPHABET, full stop. It used to hand back "was the
         * emoji board really up" so a conditional {@code clearPkbSymbolMode()} could follow the
         * board report; that clear could never fire (the mode reset ran first) and the rule it
         * described is not wanted. See {@link CrossAxisRules.LayoutEffect#LEAVE_EMOJI_MODE}.
         */
        void leaveEmojiMode();

        /**
         * Reset a PKB symbol mode to the alphabet and reload the alphabet keyboard.
         * ({@code KeyboardSwitcher.clearPkbSymbolMode}; a no-op off a PKB or outside symbol mode.)
         */
        void clearPkbSymbolMode();

        // ── board ─────────────────────────────────────────────────────────

        /** Whether {@code keyCode}'s board has its view up. */
        boolean isBoardUp(int keyCode);

        /** Whether the bar is up AND at least one board is open — the sweep's own precondition. */
        boolean anyBoardIsOpen();

        /**
         * Sweep every open board closed and refresh the bar. The exemption decision is made here,
         * by the table — never inside the sweep.
         */
        void sweepBoardsClosed();

        /**
         * Sweep every board closed with no exemptions at all — the {@code CLOSE_ALL} column. A
         * different mechanism from {@link #sweepBoardsClosed()}, which carries the commit-rebuild
         * exempt set.
         */
        void sweepAllBoardsClosed();

        /** Open {@code keyCode}'s board, closing whatever else was open (composite — see above). */
        void openBoard(int keyCode);

        /** Close {@code keyCode}'s board (composite — see above). */
        void closeBoard(int keyCode);

        /** Report {@code keyCode}'s board open — it raised itself; this only records it. */
        void reportBoardOpened(int keyCode);

        /**
         * Report {@code keyCode}'s board closed — SCOPED to that keycode, never "nothing is open
         * at all". The unscoped version is what made the coordinator forget the open voice board on
         * the KEY2 every time a dictated word committed text.
         */
        void reportBoardClosed(int keyCode);

        // ── bar ───────────────────────────────────────────────────────────

        /** Whether the UIM is enabled in settings. */
        boolean isUimEnabled();

        /** Whether the UIM bar is currently up. */
        boolean isBarShowing();

        /** Raise the UIM bar with the emoji board's keys on it, as the symbol path does. */
        void showUimBar();

        /** Take the UIM bar down. */
        void hideUimBar();

        /** Put back whichever of the UIM bar and the suggestion strip this editor gets. */
        void restoreStripOrUimBar();

        /**
         * Whether the arrow bar can be raised at all (it exists and is not already up). The guard
         * spans the whole {@code ENTER_CURSOR_MODE} bar+board effect, so the funnel asks it once
         * before applying either column.
         */
        boolean canRaiseArrowBar();

        /**
         * The step before the arrow bar goes up: retire the composing word and take the suggestion
         * views down. The composing-word cancel is the text pipeline, which the table deliberately
         * does not carry — it travels with the suggestion-view hide because production runs the two
         * back to back and nothing may come between them.
         */
        void prepareForArrowBar();

        /** Raise the arrow bar. */
        void raiseArrowBar();

        /**
         * Take the arrow bar down, restoring the strip behind it.
         *
         * @param requestShiftUpdate see {@link KeyboardTransition#requestShiftUpdate()}.
         */
        void lowerArrowBarRestoringStrip(boolean requestShiftUpdate);

        // ── cursor ────────────────────────────────────────────────────────

        /**
         * Turn cursor (FCC) mode on — {@code FccController.onFccEnabled} plus the mode flag.
         * The tracker window and the auto-disable timer stay with the caller: they are cursor-axis
         * internals, not cross-axis rules.
         */
        void cursorModeOn(boolean forced);

        /** Turn cursor (FCC) mode off — the mode flag. */
        void cursorModeOff();
    }

    private final Axes axes;

    public KeyboardStateCoordinator(Axes axes) {
        this.axes = axes;
    }

    /**
     * Apply one declarative change to all four axes.
     *
     * <p>The order is the order production already used, per kind:
     * <ul>
     *   <li>ordinary transitions: <b>bar → layout → board</b> (a board about to open needs the bar
     *       up first, so the key-painting pass can see it);</li>
     *   <li>{@code ENTER_CURSOR_MODE}: <b>cursor → (board+bar)</b> — the flag goes on before the
     *       arrow bar replaces the boards, which is what {@code FccController} reads;</li>
     *   <li>{@code EXIT_CURSOR_MODE}: <b>cursor → bar</b>.</li>
     * </ul>
     */
    public void apply(KeyboardTransition transition) {
        CrossAxisRules.Rule rule = CrossAxisRules.ruleFor(transition);
        CrossAxisRules.BarEffect bar = CrossAxisRules.barFor(transition);

        switch (rule.cursor()) {
            case ON:
                axes.cursorModeOn(transition.forced());
                // The forced entry (an FCC-driven one, z3) deliberately leaves the boards and the
                // bar alone: it is reconciling state the caller has already arranged on screen.
                if (!transition.forced()) {
                    enterCursorModeSurfaces(rule, bar, transition);
                }
                return;
            case OFF:
                axes.cursorModeOff();
                applyBar(bar, transition);
                return;
            default:
                break;
        }

        applyLayout(transition);
        applyBar(bar, transition);
        applyBoard(rule.board(), transition);
    }

    /**
     * {@code ENTER_CURSOR_MODE}'s board and bar columns, in the order production uses: take the
     * suggestion views down, sweep the boards, raise the arrow bar. The arrow bar's own "can it go
     * up at all" guard covers all three — with the bar already showing, none of it runs.
     *
     * <p>Phase 1a applied these as ONE opaque mechanism call, because {@code showArrowBar} closed
     * the boards twice (once through an inverted early return that did nothing while the UIM was
     * enabled) and the columns could not be told apart. Owner ruling R4 deleted that first close,
     * and with it gone each column is its own call again.
     */
    private void enterCursorModeSurfaces(
            CrossAxisRules.Rule rule, CrossAxisRules.BarEffect bar, KeyboardTransition transition) {
        if (bar != CrossAxisRules.BarEffect.SHOW_ARROW_BAR) {
            applyBar(bar, transition);
            applyBoard(rule.board(), transition);
            return;
        }
        if (!axes.canRaiseArrowBar()) {
            return;
        }
        axes.prepareForArrowBar();
        applyBoard(rule.board(), transition);
        axes.raiseArrowBar();
    }

    private void applyLayout(KeyboardTransition transition) {
        switch (CrossAxisRules.layoutFor(transition)) {
            case LEAVE_EMOJI_MODE:
                axes.leaveEmojiMode();
                break;
            case CLEAR_PKB_SYMBOL_MODE:
                if (transition.kind() == KeyboardTransition.Kind.OPEN_BOARD
                        || transition.kind() == KeyboardTransition.Kind.CLOSE_BOARD) {
                    // Composite: performed inside UnifiedInputBoardManager.beginBoardTransition(),
                    // the prologue both halves of the toggle share. It has to run before the open
                    // or the close, because it re-enters the keyboard loader — so the mechanism
                    // owns it and the funnel must not run it a second time.
                    break;
                }
                axes.clearPkbSymbolMode();
                break;
            case UNCHANGED:
            default:
                break;
        }
    }

    private void applyBoard(CrossAxisRules.BoardEffect effect, KeyboardTransition transition) {
        switch (effect) {
            case CLOSE_ALL_UNLESS_EXEMPT:
                // The gate the sweep has always carried: nothing to sweep unless the bar is up and
                // something is open. Then one exempt board being up spares the WHOLE sweep.
                if (axes.anyBoardIsOpen() && !CrossAxisRules.anyExemptBoardIsUp(axes::isBoardUp)) {
                    axes.sweepBoardsClosed();
                }
                break;
            case OPEN_EXCLUSIVE:
                axes.openBoard(transition.boardKeyCode());
                break;
            case CLOSE:
                axes.closeBoard(transition.boardKeyCode());
                break;
            case REPORT_OPENED:
                axes.reportBoardOpened(transition.boardKeyCode());
                break;
            case REPORT_CLOSED:
                axes.reportBoardClosed(transition.boardKeyCode());
                break;
            case CLOSE_ALL:
                // No exemptions: the arrow bar takes the space the board was using.
                axes.sweepAllBoardsClosed();
                break;
            case UNCHANGED:
            default:
                break;
        }
    }

    private void applyBar(CrossAxisRules.BarEffect effect, KeyboardTransition transition) {
        switch (effect) {
            case SHOW_UIM_IF_HIDDEN:
                if (axes.isUimEnabled() && !axes.isBarShowing()) {
                    axes.showUimBar();
                }
                break;
            case SHOW_UIM:
                axes.showUimBar();
                break;
            case HIDE_UIM:
                axes.hideUimBar();
                break;
            case SHOW_ARROW_BAR:
                // Reached only from enterCursorModeSurfaces(), which applies this column in three
                // steps around the board column. Kept here so the switch stays total.
                if (axes.canRaiseArrowBar()) {
                    axes.prepareForArrowBar();
                    axes.raiseArrowBar();
                }
                break;
            case HIDE_ARROW_BAR:
                axes.lowerArrowBarRestoringStrip(transition.requestShiftUpdate());
                break;
            case RESTORE_STRIP:
                // Composite: performed inside closeBoard(-42), which holds the pre-close snapshot
                // ("was this board actually open") that the rule needs.
                break;
            case RESTORE_STRIP_OR_UIM:
                axes.restoreStripOrUimBar();
                break;
            case UNCHANGED:
            default:
                break;
        }
    }
}
