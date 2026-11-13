package dev.bbkb.ime.keyboard.state;

import java.util.EnumMap;
import java.util.Map;

/**
 * What each {@link KeyboardTransition} does to each axis: the single statement of "what closes
 * what". Every keyboard has a board identity, and all eight boards are registered in the UIM's
 * component map; {@link #isPanelBoard(int)} separates panels from the two typing boards.
 *
 * <table>
 *   <caption>The boards and their properties</caption>
 *   <tr><th>board</th><th>keycode</th><th>typed from?</th><th>survives a commit rebuild</th>
 *       <th>survives a physical text key</th><th>raises the UIM bar</th></tr>
 *   <tr><td>alphabet</td><td>-3</td><td>yes</td><td>it IS the rebuild</td><td>yes</td><td>no</td></tr>
 *   <tr><td>symbol (on-screen)</td><td>-22</td><td>yes</td><td>no, the rebuild replaces it</td>
 *       <td>page-dependent, owned by the symbol board</td><td>no</td></tr>
 *   <tr><td>symbol (PKB)</td><td>-22</td><td>yes</td><td>no</td>
 *       <td>page-dependent, owned by the symbol board</td><td>no</td></tr>
 *   <tr><td>emoji</td><td>-11</td><td>no</td><td>no</td>
 *       <td>no (its own text keys go to its search)</td><td>yes</td></tr>
 *   <tr><td>cursor / FCC</td><td>-42</td><td>yes</td><td><b>yes</b></td><td><b>yes</b></td>
 *       <td>yes</td></tr>
 *   <tr><td>number pad</td><td>-46</td><td>yes</td><td><b>yes</b></td><td><b>yes</b></td>
 *       <td>yes</td></tr>
 *   <tr><td>voice</td><td>-27</td><td>dictated from</td><td><b>yes</b></td><td>no</td>
 *       <td>yes</td></tr>
 *   <tr><td>clipboard</td><td>-25</td><td>no</td><td>no</td><td>no</td><td>yes</td></tr>
 *   <tr><td>autofill strip</td><td>-37</td><td>no</td><td>not a board at all</td><td>no</td>
 *       <td>n/a</td></tr>
 * </table>
 *
 * <p>The symbol board's page and entry method (1 = Sym key, 2 = PKB on-screen SYM, 3 = VKB SYM,
 * 0 = spent) are properties of that board and stay in {@code KeyboardState}.
 *
 * <pre>
 * transition               | layout                | board                   | bar                 | cursor
 * -------------------------+-----------------------+-------------------------+---------------------+-----------
 * SWITCH_LAYOUT(ALPHABET)  | caller loads it       | CLOSE_ALL_UNLESS_EXEMPT | UNCHANGED           | UNCHANGED
 * SWITCH_LAYOUT(SYMBOL)    | caller loads it       | CLOSE_ALL_UNLESS_EXEMPT | UNCHANGED           | UNCHANGED
 * SWITCH_LAYOUT(UNCHANGED) | caller loads it       | CLOSE_ALL_UNLESS_EXEMPT | UNCHANGED           | UNCHANGED
 *   = the Sym page turn    |                       |                         |                     |
 * SWEEP_BOARDS             | UNCHANGED             | CLOSE_ALL_UNLESS_EXEMPT | UNCHANGED           | UNCHANGED
 * OPEN_BOARD               | CLEAR_PKB_SYMBOL_MODE | OPEN_EXCLUSIVE          | SHOW_UIM_IF_HIDDEN  | UNCHANGED
 * CLOSE_BOARD              | CLEAR_PKB_SYMBOL_MODE | CLOSE                   | UNCHANGED *         | UNCHANGED
 * BOARD_OPENING            | CLEAR_PKB_SYMBOL_MODE | UNCHANGED               | UNCHANGED           | UNCHANGED
 * BOARD_RAISED             | UNCHANGED             | REPORT_OPENED           | SHOW_UIM_IF_HIDDEN  | UNCHANGED
 * BOARD_LOWERED            | LEAVE_EMOJI_MODE **   | REPORT_CLOSED           | UNCHANGED           | UNCHANGED
 * ENTER_CURSOR_MODE        | UNCHANGED             | CLOSE_ALL               | SHOW_ARROW_BAR      | ON
 * EXIT_CURSOR_MODE         | UNCHANGED             | UNCHANGED               | HIDE_ARROW_BAR      | OFF
 * SHOW_BAR                 | UNCHANGED             | UNCHANGED               | SHOW_UIM            | UNCHANGED
 * HIDE_BAR                 | UNCHANGED             | UNCHANGED               | HIDE_UIM            | UNCHANGED
 * RESTORE_BAR              | UNCHANGED             | UNCHANGED               | RESTORE_STRIP_OR_UIM| UNCHANGED
 * </pre>
 *
 * <p>No layout load touches the bar: the Sym board comes up under whichever bar is showing.
 * * No board close touches the bar; what it returns to after the last board closes is
 * {@code AuxBarChoice}'s. ** {@code LEAVE_EMOJI_MODE} for board -11 only, {@code UNCHANGED} otherwise.
 *
 * <p>{@code OPEN_BOARD} / {@code CLOSE_BOARD} are for boards the funnel opens itself.
 * {@code BOARD_OPENING} + {@code BOARD_RAISED} together are the {@code OPEN_BOARD} row, split for a
 * board raised by its own mechanism (emoji) because that mechanism spans the mode change.
 *
 * <p>Two key-press policies with different exempt sets, on purpose: the commit-driven rebuild
 * ({@link #boardsExemptFromCommitRebuild()}) spares FCC, the number pad and voice; a physical text
 * key ({@link #boardsExemptFromTextKey()}) spares FCC and the number pad, since typing ends
 * dictation. The emoji board's text keys go to its search query first ({@code EmojiQueryKeyRouting}).
 *
 * <p>Not expressed here: the composing-word cancel / suggestion re-request around cursor mode (text
 * pipeline), the slideboard raise on {@code SWITCH_LAYOUT}, and the payload-carrying bar calls
 * ({@code showSuggestionStrip}, {@code showAccentBar}); those stay at their call sites.
 */
public final class CrossAxisRules {

    private CrossAxisRules() {
    }

    /** What a transition does to the layout axis. */
    public enum LayoutEffect {
        UNCHANGED,
        /**
         * Leave EMOJI mode without firing UI callbacks: take the palettes down, restore the main
         * keyboard view, and return to ALPHABET (never to a PKB symbol page).
         */
        LEAVE_EMOJI_MODE,
        /** Reset a PKB symbol mode back to the alphabet before a board takes the screen. */
        CLEAR_PKB_SYMBOL_MODE,
    }

    /** What a transition does to the board axis. */
    public enum BoardEffect {
        UNCHANGED,
        /** Sweep every open board closed, unless an exempt board is up (which spares them all). */
        CLOSE_ALL_UNLESS_EXEMPT,
        /** Sweep every open board closed, exemptions included. */
        CLOSE_ALL,
        /** Open this board, closing whatever else was open. */
        OPEN_EXCLUSIVE,
        /** Close this board. */
        CLOSE,
        /** Report this board open: it raised itself; the funnel only records it. */
        REPORT_OPENED,
        /** Report this board closed, scoped to its keycode (never "nothing is open at all"). */
        REPORT_CLOSED,
    }

    /** What a transition does to the bar axis. */
    public enum BarEffect {
        UNCHANGED,
        /** Raise the UIM bar if the UIM is enabled and the bar is not already up. */
        SHOW_UIM_IF_HIDDEN,
        /** Raise the UIM bar. */
        SHOW_UIM,
        /** Take the UIM bar down. */
        HIDE_UIM,
        /** Hide the suggestion views, then raise the arrow bar. */
        SHOW_ARROW_BAR,
        /** Take the arrow bar down, then restore the suggestion strip. */
        HIDE_ARROW_BAR,
        /**
         * Put back the UIM bar when this editor uses it, the suggestion strip otherwise. The bar
         * axis' one entry point with a decision in it.
         */
        RESTORE_STRIP_OR_UIM,
    }

    /** What a transition does to the cursor axis. */
    public enum CursorEffect {
        UNCHANGED,
        ON,
        OFF,
    }

    public static final class Rule {
        private final LayoutEffect layout;
        private final BoardEffect board;
        private final BarEffect bar;
        private final CursorEffect cursor;

        private Rule(LayoutEffect layout, BoardEffect board, BarEffect bar, CursorEffect cursor) {
            this.layout = layout;
            this.board = board;
            this.bar = bar;
            this.cursor = cursor;
        }

        public LayoutEffect layout() {
            return layout;
        }

        public BoardEffect board() {
            return board;
        }

        public BarEffect bar() {
            return bar;
        }

        public CursorEffect cursor() {
            return cursor;
        }

        @Override
        public String toString() {
            return "layout=" + layout + " board=" + board + " bar=" + bar + " cursor=" + cursor;
        }
    }

    private static final Map<KeyboardTransition.Kind, Rule> ROWS =
            new EnumMap<>(KeyboardTransition.Kind.class);

    static {
        ROWS.put(KeyboardTransition.Kind.SWITCH_LAYOUT, new Rule(
                LayoutEffect.UNCHANGED,
                BoardEffect.CLOSE_ALL_UNLESS_EXEMPT,
                BarEffect.UNCHANGED,
                CursorEffect.UNCHANGED));
        ROWS.put(KeyboardTransition.Kind.SWEEP_BOARDS, new Rule(
                LayoutEffect.UNCHANGED,
                BoardEffect.CLOSE_ALL_UNLESS_EXEMPT,
                BarEffect.UNCHANGED,
                CursorEffect.UNCHANGED));
        ROWS.put(KeyboardTransition.Kind.OPEN_BOARD, new Rule(
                LayoutEffect.CLEAR_PKB_SYMBOL_MODE,
                BoardEffect.OPEN_EXCLUSIVE,
                BarEffect.SHOW_UIM_IF_HIDDEN,
                CursorEffect.UNCHANGED));
        ROWS.put(KeyboardTransition.Kind.CLOSE_BOARD, new Rule(
                LayoutEffect.CLEAR_PKB_SYMBOL_MODE,
                BoardEffect.CLOSE,
                BarEffect.UNCHANGED,
                CursorEffect.UNCHANGED));
        ROWS.put(KeyboardTransition.Kind.BOARD_OPENING, new Rule(
                LayoutEffect.CLEAR_PKB_SYMBOL_MODE,
                BoardEffect.UNCHANGED,
                BarEffect.UNCHANGED,
                CursorEffect.UNCHANGED));
        ROWS.put(KeyboardTransition.Kind.BOARD_RAISED, new Rule(
                LayoutEffect.UNCHANGED,
                BoardEffect.REPORT_OPENED,
                BarEffect.SHOW_UIM_IF_HIDDEN,
                CursorEffect.UNCHANGED));
        ROWS.put(KeyboardTransition.Kind.BOARD_LOWERED, new Rule(
                LayoutEffect.UNCHANGED,       // LEAVE_EMOJI_MODE for -11; see layoutFor(transition)
                BoardEffect.REPORT_CLOSED,
                BarEffect.UNCHANGED,
                CursorEffect.UNCHANGED));
        ROWS.put(KeyboardTransition.Kind.ENTER_CURSOR_MODE, new Rule(
                LayoutEffect.UNCHANGED,
                BoardEffect.CLOSE_ALL,
                BarEffect.SHOW_ARROW_BAR,
                CursorEffect.ON));
        ROWS.put(KeyboardTransition.Kind.EXIT_CURSOR_MODE, new Rule(
                LayoutEffect.UNCHANGED,
                BoardEffect.UNCHANGED,
                BarEffect.HIDE_ARROW_BAR,
                CursorEffect.OFF));
        ROWS.put(KeyboardTransition.Kind.SHOW_BAR, new Rule(
                LayoutEffect.UNCHANGED,
                BoardEffect.UNCHANGED,
                BarEffect.SHOW_UIM,
                CursorEffect.UNCHANGED));
        ROWS.put(KeyboardTransition.Kind.HIDE_BAR, new Rule(
                LayoutEffect.UNCHANGED,
                BoardEffect.UNCHANGED,
                BarEffect.HIDE_UIM,
                CursorEffect.UNCHANGED));
        ROWS.put(KeyboardTransition.Kind.RESTORE_BAR, new Rule(
                LayoutEffect.UNCHANGED,
                BoardEffect.UNCHANGED,
                BarEffect.RESTORE_STRIP_OR_UIM,
                CursorEffect.UNCHANGED));
    }

    /** The row for {@code transition}'s kind; throws if a kind has no row. */
    public static Rule ruleFor(KeyboardTransition transition) {
        Rule rule = ROWS.get(transition.kind());
        if (rule == null) {
            throw new IllegalStateException("no cross-axis rule for " + transition.kind());
        }
        return rule;
    }

    /**
     * The layout effect for this exact transition. Only {@code BOARD_LOWERED} depends on more than
     * the kind: emoji is the one board whose close is also a layout-mode change.
     */
    public static LayoutEffect layoutFor(KeyboardTransition transition) {
        if (transition.kind() == KeyboardTransition.Kind.BOARD_LOWERED) {
            return transition.boardKeyCode() == EMOJI_KEY_CODE
                    ? LayoutEffect.LEAVE_EMOJI_MODE
                    : LayoutEffect.UNCHANGED;
        }
        return ruleFor(transition).layout();
    }

    public static BarEffect barFor(KeyboardTransition transition) {
        return ruleFor(transition).bar();
    }

    /**
     * The alphabet board, up when no other is. -3 is already "back to the main keyboard" on the UIM
     * bar and in {@code KeyboardState.onCodeInput}. Registered ({@code AlphabetBoardController}) but
     * not a panel board.
     */
    public static final int ALPHABET_BOARD_KEY_CODE = -3;

    /**
     * The symbol board, on-screen and PKB alike; -22 is the hardware Sym key's code. Lives in the
     * main keyboard view; registered ({@code SymbolBoardController}) but not a panel board.
     */
    public static final int SYMBOL_BOARD_KEY_CODE = -22;

    /** The keyboard menu. A layout mode that opens through {@code showKeyboardMenu}. */
    public static final int MENU_BOARD_KEY_CODE = -23;

    /** Emoji board, opened and closed by the board rows. */
    public static final int EMOJI_KEY_CODE = -11;

    public static final int CLIPBOARD_KEY_CODE = -25;

    /** FCC / cursor board. Typed from. */
    public static final int CURSOR_BOARD_KEY_CODE = -42;

    /** Number pad. Typed from. */
    public static final int NUMBER_PAD_KEY_CODE = -46;

    /** Voice input. Dictated from: the commit of the result is what runs the sweep. */
    public static final int VOICE_KEY_CODE = -27;

    /**
     * The inline autofill strip. Registered in the component map but not a board: it has no bar
     * toggle, it is the component every sweep spares by name, and it has never counted as open.
     */
    public static final int AUTOFILL_KEY_CODE = -37;

    /**
     * The typing boards: drawn in the main keyboard view rather than in a panel over it. Filters
     * the component map; {@link #MENU_BOARD_KEY_CODE} is absent because nothing registers it.
     */
    private static final int[] TYPING_BOARDS = {
            ALPHABET_BOARD_KEY_CODE,
            SYMBOL_BOARD_KEY_CODE,
    };

    /**
     * Whether this board is a panel drawn over the main keyboard view. Everything that iterates the
     * component map meaning panels must ask this (sweeps, exclusive open, bar painting and taps);
     * "which board is up" is {@code KeyboardSwitcher.activeBoard()}.
     */
    public static boolean isPanelBoard(int keyCode) {
        return !contains(TYPING_BOARDS, keyCode);
    }

    public static boolean isTypingBoard(int keyCode) {
        return contains(TYPING_BOARDS, keyCode);
    }

    /** The board keycode a layout value names, or {@link KeyboardTransition#NO_BOARD}. */
    public static int boardKeyCodeFor(KeyboardTransition.Layout layout) {
        switch (layout) {
            case ALPHABET:
                return ALPHABET_BOARD_KEY_CODE;
            case SYMBOL:
                return SYMBOL_BOARD_KEY_CODE;
            case EMOJI:
                return EMOJI_KEY_CODE;
            case MENU:
                return MENU_BOARD_KEY_CODE;
            case UNCHANGED:
            default:
                return KeyboardTransition.NO_BOARD;
        }
    }

    /**
     * Boards that survive the rebuild after every committed character ({@code applyPostEventUpdates}
     * then {@code setAlphabetKeyboard}), in probe order. A board left off is closed by the user's own
     * text. One being up skips the whole sweep, not just itself.
     */
    private static final int[] BOARDS_EXEMPT_FROM_COMMIT_REBUILD = {
            CURSOR_BOARD_KEY_CODE,
            NUMBER_PAD_KEY_CODE,
            VOICE_KEY_CODE,
    };

    /** Copied so callers cannot rewrite it. */
    public static int[] boardsExemptFromCommitRebuild() {
        return BOARDS_EXEMPT_FROM_COMMIT_REBUILD.clone();
    }

    public static boolean boardSurvivesCommitRebuild(int keyCode) {
        return contains(BOARDS_EXEMPT_FROM_COMMIT_REBUILD, keyCode);
    }

    /**
     * Boards that survive a physical text key ({@code BlackBerryIME.dismissBoardsForTextKey}, on a
     * non-Shift, non-Enter, non-board key-down). Voice is left out on purpose: typing ends dictation.
     */
    private static final int[] BOARDS_EXEMPT_FROM_TEXT_KEY = {
            CURSOR_BOARD_KEY_CODE,
            NUMBER_PAD_KEY_CODE,
    };

    /** Copied so callers cannot rewrite it. */
    public static int[] boardsExemptFromTextKey() {
        return BOARDS_EXEMPT_FROM_TEXT_KEY.clone();
    }

    public static boolean boardSurvivesTextKey(int keyCode) {
        return contains(BOARDS_EXEMPT_FROM_TEXT_KEY, keyCode);
    }

    /** Answers "is this board's view up", for the exemption probe. */
    public interface BoardIsUp {
        boolean test(int keyCode);
    }

    /** The first commit-rebuild-exempt board that is up, or {@link KeyboardTransition#NO_BOARD}. */
    public static int firstExemptBoardUp(BoardIsUp isUp) {
        for (int exempt : BOARDS_EXEMPT_FROM_COMMIT_REBUILD) {
            if (isUp.test(exempt)) {
                return exempt;
            }
        }
        return KeyboardTransition.NO_BOARD;
    }

    /** Whether the sweep of {@link BoardEffect#CLOSE_ALL_UNLESS_EXEMPT} must be skipped. */
    public static boolean anyExemptBoardIsUp(BoardIsUp isUp) {
        return firstExemptBoardUp(isUp) != KeyboardTransition.NO_BOARD;
    }

    private static boolean contains(int[] set, int keyCode) {
        for (int member : set) {
            if (member == keyCode) {
                return true;
            }
        }
        return false;
    }
}
