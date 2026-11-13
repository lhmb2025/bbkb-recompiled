package dev.bbkb.ime.keyboard.state;

/**
 * The one funnel: {@link #apply(KeyboardTransition)} applies a declarative change to all four axes
 * as {@link CrossAxisRules} prescribes. Pure decision logic; every side effect sits behind
 * {@link Axes} (production: {@code KeyboardSwitcher}), so it runs on the JVM against a fake.
 *
 * <p>{@code OPEN_EXCLUSIVE} and {@code CLOSE} are one mechanism call each:
 * {@code UnifiedInputBoardManager.openBoard} / {@code closeBoard} do the layout clear and bar
 * restore themselves, and the bar restore needs a snapshot taken before the close.
 */
public final class KeyboardStateCoordinator {
    /** The four axes' mechanisms, as the funnel needs them. */
    public interface Axes {
        /**
         * Leave EMOJI mode without UI callbacks, take the palettes down and restore the main
         * keyboard view, returning to ALPHABET (the layout column of {@code BOARD_LOWERED(-11)}).
         */
        void leaveEmojiMode();

        /** Reset a PKB symbol mode to the alphabet and reload it; no-op off a PKB or symbol mode. */
        void clearPkbSymbolMode();

        boolean isBoardUp(int keyCode);

        /** Whether the bar is up and at least one board is open (the sweep's precondition). */
        boolean anyBoardIsOpen();

        /** Sweep every open board closed and refresh the bar. Exemptions are decided by the table. */
        void sweepBoardsClosed();

        /** Sweep every board closed with no exemptions (the {@code CLOSE_ALL} column). */
        void sweepAllBoardsClosed();

        /** Open {@code keyCode}'s board, closing whatever else was open (composite, see class doc). */
        void openBoard(int keyCode);

        void closeBoard(int keyCode);

        /** Report {@code keyCode}'s board open: it raised itself; this only records it. */
        void reportBoardOpened(int keyCode);

        /**
         * Report {@code keyCode}'s board closed, scoped to that keycode. Never "nothing is open at
         * all", or an open voice board is forgotten when a dictated word commits.
         */
        void reportBoardClosed(int keyCode);

        boolean isUimEnabled();

        boolean isBarShowing();

        /** Raise the UIM bar with the emoji board's keys on it, as the symbol path does. */
        void showUimBar();

        void hideUimBar();

        /** Put back whichever of the UIM bar and the suggestion strip this editor gets. */
        void restoreStripOrUimBar();

        /**
         * Whether the arrow bar exists and is not already up. Guards the whole
         * {@code ENTER_CURSOR_MODE} bar+board effect, so it is asked once before either column.
         */
        boolean canRaiseArrowBar();

        /**
         * Before the arrow bar goes up: cancel the composing word and take the suggestion views
         * down. The two run back to back; nothing may come between them.
         */
        void prepareForArrowBar();

        void raiseArrowBar();

        /** Take the arrow bar down, restoring the strip behind it. */
        void lowerArrowBarRestoringStrip(boolean requestShiftUpdate);

        /**
         * Turn cursor (FCC) mode on ({@code FccController.onFccEnabled} plus the mode flag). The
         * tracker window and auto-disable timer stay with the caller.
         */
        void cursorModeOn(boolean forced);

        void cursorModeOff();
    }

    private final Axes axes;

    public KeyboardStateCoordinator(Axes axes) {
        this.axes = axes;
    }

    /**
     * Order per kind: ordinary transitions layout, bar, board (the bar must be up before a board
     * opens so the key-painting pass sees it); {@code ENTER_CURSOR_MODE} cursor, then board+bar
     * ({@code FccController} reads the flag); {@code EXIT_CURSOR_MODE} cursor, then bar.
     */
    public void apply(KeyboardTransition transition) {
        CrossAxisRules.Rule rule = CrossAxisRules.ruleFor(transition);
        CrossAxisRules.BarEffect bar = CrossAxisRules.barFor(transition);

        switch (rule.cursor()) {
            case ON:
                axes.cursorModeOn(transition.forced());
                // A forced (FCC-driven) entry reconciles state already on screen: leave boards and bar.
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
     * {@code ENTER_CURSOR_MODE}'s board and bar columns: suggestion views down, sweep the boards,
     * raise the arrow bar. The arrow bar's guard covers all three.
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
                    // Done inside UnifiedInputBoardManager.beginBoardTransition() before the open or
                    // close (it re-enters the keyboard loader); must not run a second time here.
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
                // One exempt board being up spares the whole sweep.
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
                // Reached only via enterCursorModeSurfaces(); kept so the switch stays total.
                if (axes.canRaiseArrowBar()) {
                    axes.prepareForArrowBar();
                    axes.raiseArrowBar();
                }
                break;
            case HIDE_ARROW_BAR:
                axes.lowerArrowBarRestoringStrip(transition.requestShiftUpdate());
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
