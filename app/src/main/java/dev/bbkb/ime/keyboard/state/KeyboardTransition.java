package dev.bbkb.ime.keyboard.state;

/**
 * One declarative keyboard-state change, applied to all four axes at once.
 *
 * <p>The keyboard's state lives on four axes with four different owners:
 *
 * <table>
 *   <caption>The four axes</caption>
 *   <tr><th>Axis</th><th>State</th><th>Owner</th></tr>
 *   <tr><td>layout</td>
 *       <td>{@code KeyboardState.currentMode} (ALPHABET / SYMBOL / EMOJI / MENU), symbol page,
 *           {@code symbolEntryMethod}</td>
 *       <td>{@code KeyboardState}</td></tr>
 *   <tr><td>board</td>
 *       <td>{@code activeBoard} keycode</td>
 *       <td>{@code UnifiedBoardCoordinator} </td></tr>
 *   <tr><td>bar</td>
 *       <td>{@code AuxBarState} (suggestions / UIM / arrow / accent / none)</td>
 *       <td>{@code AuxBarManager} + {@code AuxBarView}</td></tr>
 *   <tr><td>cursor</td>
 *       <td>{@code BlackBerryIME.isCursorModeEnabled} + the cursor-tracker window</td>
 *       <td>{@code BlackBerryIME}</td></tr>
 * </table>
 *
 * <p>A transition names the change; {@link CrossAxisRules} decides what it does to the other
 * axes. Immutable; construct through the factories.
 */
public final class KeyboardTransition {

    /** The declarative changes a caller can ask for. One row of {@link CrossAxisRules} each. */
    public enum Kind {
        /**
         * The main keyboard is about to be rebuilt for a new layout (alphabet page, symbol page,
         * emoji, menu). Raised <em>before</em> the new keyboard is installed.
         */
        SWITCH_LAYOUT,
        /**
         * Sweep the open boards closed with the commit-rebuild exemptions: a layout change's board
         * column without the layout change (raised when the UIM bar is off).
         */
        SWEEP_BOARDS,
        /** A board is being opened exclusively (the host closes whatever else was open). */
        OPEN_BOARD,
        CLOSE_BOARD,
        /**
         * A board is about to be raised by its own mechanism: run {@link #OPEN_BOARD}'s prologue
         * (the layout column) before the board takes the mode.
         */
        BOARD_OPENING,
        /** A board has been raised by its own mechanism: {@link #OPEN_BOARD}'s epilogue. */
        BOARD_RAISED,
        /** A board has been lowered by its own mechanism. */
        BOARD_LOWERED,
        ENTER_CURSOR_MODE,
        EXIT_CURSOR_MODE,
        SHOW_BAR,
        HIDE_BAR,
        /** Put back whichever of the UIM bar and the suggestion strip this editor gets. */
        RESTORE_BAR,
    }

    /**
     * The layout axis' values ({@code KeyboardState.KeyboardModeState} seen from outside). Each also
     * names a board ({@link CrossAxisRules#boardKeyCodeFor(Layout)}). No {@link Kind#SWITCH_LAYOUT}
     * carries {@link #EMOJI} or {@link #MENU} today; they complete the value set.
     */
    public enum Layout {
        ALPHABET,
        /** A symbol keyboard: the on-screen one, or the PKB one the Sym key opens. */
        SYMBOL,
        EMOJI,
        MENU,
        /**
         * "Whatever is loaded": board transitions, and the Sym page turn, which does not yet know
         * whether it lands on another symbol page or back on the alphabet.
         */
        UNCHANGED,
    }

    /** No board is involved in this transition. Mirrors {@code UnifiedBoardCoordinator.NO_BOARD}. */
    public static final int NO_BOARD = 0;

    private final Kind kind;
    private final Layout layout;
    private final int boardKeyCode;
    private final boolean forced;
    private final boolean requestShiftUpdate;

    private KeyboardTransition(
            Kind kind, Layout layout, int boardKeyCode, boolean forced, boolean requestShiftUpdate) {
        this.kind = kind;
        this.layout = layout;
        this.boardKeyCode = boardKeyCode;
        this.forced = forced;
        this.requestShiftUpdate = requestShiftUpdate;
    }

    public Kind kind() {
        return kind;
    }

    public Layout layout() {
        return layout;
    }

    /** The board this transition is about, or {@link #NO_BOARD}. */
    public int boardKeyCode() {
        return boardKeyCode;
    }

    /**
     * A forced cursor-mode entry is FCC reconciling a state already on screen: it turns the mode on
     * but leaves the board and bar columns alone and does not arm the 4 s auto-disable.
     */
    public boolean forced() {
        return forced;
    }

    /**
     * {@link Kind#EXIT_CURSOR_MODE} only: whether restoring the strip also re-derives the shift
     * state (set by the physical-backspace path).
     */
    public boolean requestShiftUpdate() {
        return requestShiftUpdate;
    }

    /** The main keyboard is about to be rebuilt for {@code layout}. */
    public static KeyboardTransition switchLayout(Layout layout) {
        return new KeyboardTransition(Kind.SWITCH_LAYOUT, layout, NO_BOARD, false, false);
    }

    public static KeyboardTransition sweepBoards() {
        return new KeyboardTransition(Kind.SWEEP_BOARDS, Layout.UNCHANGED, NO_BOARD, false, false);
    }

    public static KeyboardTransition openBoard(int keyCode) {
        return new KeyboardTransition(Kind.OPEN_BOARD, Layout.UNCHANGED, keyCode, false, false);
    }

    public static KeyboardTransition closeBoard(int keyCode) {
        return new KeyboardTransition(Kind.CLOSE_BOARD, Layout.UNCHANGED, keyCode, false, false);
    }

    /** {@code keyCode}'s board is about to raise itself: run the open prologue. */
    public static KeyboardTransition boardOpening(int keyCode) {
        return new KeyboardTransition(Kind.BOARD_OPENING, Layout.UNCHANGED, keyCode, false, false);
    }

    /** {@code keyCode}'s board has raised itself: run the open epilogue. */
    public static KeyboardTransition boardRaised(int keyCode) {
        return new KeyboardTransition(Kind.BOARD_RAISED, Layout.UNCHANGED, keyCode, false, false);
    }

    /** {@code keyCode}'s board has lowered itself. */
    public static KeyboardTransition boardLowered(int keyCode) {
        return new KeyboardTransition(Kind.BOARD_LOWERED, Layout.UNCHANGED, keyCode, false, false);
    }

    /** @param forced see {@link #forced()} */
    public static KeyboardTransition enterCursorMode(boolean forced) {
        return new KeyboardTransition(
                Kind.ENTER_CURSOR_MODE, Layout.UNCHANGED, NO_BOARD, forced, false);
    }

    /** @param requestShiftUpdate see {@link #requestShiftUpdate()}. */
    public static KeyboardTransition exitCursorMode(boolean requestShiftUpdate) {
        return new KeyboardTransition(
                Kind.EXIT_CURSOR_MODE, Layout.UNCHANGED, NO_BOARD, false, requestShiftUpdate);
    }

    public static KeyboardTransition showBar() {
        return new KeyboardTransition(Kind.SHOW_BAR, Layout.UNCHANGED, NO_BOARD, false, false);
    }

    public static KeyboardTransition hideBar() {
        return new KeyboardTransition(Kind.HIDE_BAR, Layout.UNCHANGED, NO_BOARD, false, false);
    }

    public static KeyboardTransition restoreBar() {
        return new KeyboardTransition(Kind.RESTORE_BAR, Layout.UNCHANGED, NO_BOARD, false, false);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(kind.name());
        if (layout != Layout.UNCHANGED) {
            sb.append('(').append(layout).append(')');
        } else if (boardKeyCode != NO_BOARD) {
            sb.append('(').append(boardKeyCode).append(')');
        }
        return sb.toString();
    }
}
