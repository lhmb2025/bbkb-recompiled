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
 *       <td>{@code UnifiedBoardCoordinator} (sole owner since July 2026)</td></tr>
 *   <tr><td>bar</td>
 *       <td>{@code AuxBarState} (suggestions / UIM / arrow / accent / none)</td>
 *       <td>{@code AuxBarManager} + {@code AuxBarView}</td></tr>
 *   <tr><td>cursor</td>
 *       <td>{@code BlackBerryIME.isCursorModeEnabled} + the cursor-tracker window</td>
 *       <td>{@code BlackBerryIME}</td></tr>
 * </table>
 *
 * <p>Before this type existed, a change on one axis reached the other three through a scattered
 * set of {@code hide*} / {@code reset*} sweeps called from whichever method happened to run first
 * — {@code setKeyboard → hideEmojiKeyboard}, {@code setAlphabetKeyboard →
 * hideKeyboardOnKeyboardStateChange}, {@code showArrowBar → hideUnifiedInputBoard + hideInputBoard},
 * {@code beginBoardTransition → clearPkbSymbolMode}. Each sweep carried its own copy of "what
 * closes what", and every symbol/mic defect of 2026-09-20 was one of those copies disagreeing with
 * another. A transition names the change instead of the sweep, and {@link CrossAxisRules} — the one
 * table — decides what it does to the other three axes.
 *
 * <p>Immutable value type; construct through the factories.
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
         * Sweep the open boards closed, with the commit-rebuild exemptions — the board column of a
         * layout change without the layout change. The transition {@code BlackBerryIME}'s
         * "the UIM bar is off, so nothing should be sitting in its place" sweep raises.
         */
        SWEEP_BOARDS,
        /** A board is being opened (exclusively — the host closes whatever else was open). */
        OPEN_BOARD,
        /** A board is being closed. */
        CLOSE_BOARD,
        /**
         * A board is about to be raised <em>by its own mechanism</em>: run {@link #OPEN_BOARD}'s
         * prologue (the layout column) before the board takes the mode. See
         * {@link CrossAxisRules} for why this and {@link #BOARD_RAISED} are one rule in two halves.
         */
        BOARD_OPENING,
        /** A board has been raised by its own mechanism: {@link #OPEN_BOARD}'s epilogue. */
        BOARD_RAISED,
        /**
         * A board has been lowered by its own mechanism. Replaces Phase 1a's {@code LAYOUT_LOADED}:
         * that row was only ever "the emoji board came down on a keyboard rebuild", written as a
         * layout event because emoji did not have one board identity yet. It does now, so this is
         * an ordinary board switch.
         */
        BOARD_LOWERED,
        /** Cursor (FCC) mode is being entered. */
        ENTER_CURSOR_MODE,
        /** Cursor (FCC) mode is being left. */
        EXIT_CURSOR_MODE,
        /** The UIM bar is being raised. */
        SHOW_BAR,
        /** The UIM bar is being taken down. */
        HIDE_BAR,
        /** Put back whichever of the UIM bar and the suggestion strip this editor gets. */
        RESTORE_BAR,
    }

    /**
     * The layout axis' values — {@code KeyboardState.KeyboardModeState} seen from outside, split
     * where the cross-axis rules differ.
     *
     * <p>{@link #SYMBOL} and {@link #PKB_SYMBOL} are one mode on the layout axis but two rows of
     * the table: only the PKB symbol load raises the UIM bar. They are separate values rather than
     * an {@code isPkbDevice()} test, because a PKB can load the on-screen symbol keyboard too
     * ({@code KeyboardState.switchToSymbolFromAlphabet} always calls the VKB loader) and that load
     * must not raise the bar.
     *
     * <p>{@link #EMOJI} and {@link #MENU} are values no {@link Kind#SWITCH_LAYOUT} carries today:
     * the emoji board opens as a BOARD and the menu opens through {@code showKeyboardMenu}. They
     * are listed so the value set is complete.
     *
     * <p>Since Phase 1d every one of these values also names a BOARD —
     * {@link CrossAxisRules#boardKeyCodeFor(Layout)} — because "which keyboard is loaded" and
     * "which board is up" are the same question. The values survive as a separate enum only
     * because two of them (SYMBOL / PKB_SYMBOL) are one board with two bar rules.
     */
    public enum Layout {
        ALPHABET,
        /** The on-screen symbol keyboard. */
        SYMBOL,
        /** The PKB symbol keyboard — the one layout that raises the UIM bar. */
        PKB_SYMBOL,
        EMOJI,
        MENU,
        /**
         * "Whatever is loaded" — every transition that is about a board rather than a layout, and
         * the Sym page turn, which does not yet know whether it lands on another symbol page or
         * back on the alphabet.
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
     * The cursor axis' "forced" flag (the third argument of
     * {@code BlackBerryIME.applyCursorModeState}). A forced entry is FCC reconciling a state the
     * caller has already arranged on screen: it turns the mode on but deliberately leaves the board
     * and bar columns alone, and does not arm the 4 s auto-disable. Always false for every other
     * kind.
     */
    public boolean forced() {
        return forced;
    }

    /**
     * {@link Kind#EXIT_CURSOR_MODE} only: whether restoring the strip should also re-derive the
     * shift state — the second argument of {@code BlackBerryIME.applyCursorModeState}, which the
     * physical-backspace path sets. Always false for every other kind.
     */
    public boolean requestShiftUpdate() {
        return requestShiftUpdate;
    }

    // ───────────────────────────────────────────────────────────── factories

    /** The main keyboard is about to be rebuilt for {@code layout}. */
    public static KeyboardTransition switchLayout(Layout layout) {
        return new KeyboardTransition(Kind.SWITCH_LAYOUT, layout, NO_BOARD, false, false);
    }

    /** Sweep the open boards closed, commit-rebuild exemptions included. */
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

    /** @param forced see {@link #forced()} — FCC reconciling a state already on screen. */
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

    /** Put back whichever of the UIM bar and the suggestion strip this editor gets. */
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
