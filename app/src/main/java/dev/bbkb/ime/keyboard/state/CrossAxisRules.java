package dev.bbkb.ime.keyboard.state;

import java.util.EnumMap;
import java.util.Map;

/**
 * <b>THE table.</b> What each {@link KeyboardTransition} does to each axis.
 *
 * <p>This is the single statement of "what closes what". Every rule here used to be a separate
 * {@code hide*} / {@code reset*} sweep somewhere on the path of whichever axis happened to move
 * first, with no one place to read them together and no way to notice two of them disagreeing.
 *
 * <h2>Phase 1d: every keyboard is a board</h2>
 *
 * <p>Phase 1a left two axes with two owners. LAYOUT was {@code KeyboardState.currentMode} plus the
 * symbol page and the entry method, owned by {@code KeyboardState}; BOARD was the active keycode,
 * owned by {@code UnifiedBoardCoordinator}. Emoji sat on both, which is why its open path could not
 * be migrated: it was a layout mode AND board −11, and the two identities disagreed about what
 * opening it should clear.
 *
 * <p>Phase 1d gives <b>every</b> keyboard a board identity, so "which board is up" is one question
 * with one vocabulary — {@link #ALPHABET_BOARD_KEY_CODE} and {@link #SYMBOL_BOARD_KEY_CODE} join
 * the five panel boards — and the emoji board has ONE identity again: board −11, opened and closed
 * by the ordinary board rows below.
 *
 * <h2>Phase 1f: and every board is in the registry</h2>
 *
 * <p>Phase 1d stopped one step short: the two typing boards had identities but were not
 * {@code UnifiedInputBoardComponent}s, because four sites iterate the UIM's component map while
 * meaning "is a PANEL board up" and the alphabet board is always up. Phase 1f separates those two
 * questions — {@link #isPanelBoard(int)} / {@code UnifiedInputBoardManager.isPanelBoardShowing()}
 * for "is a panel up", {@code KeyboardSwitcher.activeBoard()} for "which board is up" — and then
 * registers both typing boards, so the component map is the single registry of all eight.
 *
 * <table>
 *   <caption>The boards and their properties</caption>
 *   <tr><th>board</th><th>keycode</th><th>typed from?</th><th>survives a commit rebuild</th>
 *       <th>survives a physical text key</th><th>raises the UIM bar</th></tr>
 *   <tr><td>alphabet</td><td>−3</td><td>yes</td><td>it IS the rebuild</td><td>yes</td><td>no</td></tr>
 *   <tr><td>symbol (on-screen)</td><td>−22</td><td>yes</td><td>no — the rebuild replaces it</td>
 *       <td>page-dependent, owned by the symbol board</td><td>no</td></tr>
 *   <tr><td>symbol (PKB)</td><td>−22</td><td>yes</td><td>no</td>
 *       <td>page-dependent, owned by the symbol board</td><td><b>yes</b></td></tr>
 *   <tr><td>emoji</td><td>−11</td><td>no</td><td>no</td>
 *       <td>no, unless dynamic search is running</td><td>yes</td></tr>
 *   <tr><td>cursor / FCC</td><td>−42</td><td>yes</td><td><b>yes</b></td><td><b>yes</b></td>
 *       <td>yes; its close restores the strip</td></tr>
 *   <tr><td>number pad</td><td>−46</td><td>yes</td><td><b>yes</b></td><td><b>yes</b> (R3b)</td>
 *       <td>yes</td></tr>
 *   <tr><td>voice</td><td>−27</td><td>dictated from</td><td><b>yes</b></td><td>no</td>
 *       <td>yes</td></tr>
 *   <tr><td>clipboard</td><td>−25</td><td>no</td><td>no</td><td>no</td><td>yes</td></tr>
 *   <tr><td>autofill strip</td><td>−37</td><td>no</td><td>not a board at all</td><td>no</td>
 *       <td>n/a</td></tr>
 * </table>
 *
 * <p>The symbol board's <b>page</b> and its <b>entry method</b> are properties of that board, not
 * axes of their own: the page is which face of −22 is showing, and the entry method is how it was
 * opened (1 = Sym key, 2 = PKB on-screen SYM, 3 = VKB SYM, 0 = spent). Both stay in
 * {@code KeyboardState}, which is the symbol board's own state; nothing outside it reads them.
 *
 * <h2>The rows</h2>
 *
 * <pre>
 * transition               | layout                | board                   | bar                 | cursor
 * -------------------------+-----------------------+-------------------------+---------------------+-----------
 * SWITCH_LAYOUT(ALPHABET)  | caller loads it       | SWEEP_UNLESS_EXEMPT     | UNCHANGED           | UNCHANGED
 * SWITCH_LAYOUT(SYMBOL)    | caller loads it       | SWEEP_UNLESS_EXEMPT     | UNCHANGED           | UNCHANGED
 * SWITCH_LAYOUT(PKB_SYMBOL)| caller loads it       | SWEEP_UNLESS_EXEMPT     | SHOW_UIM_IF_HIDDEN  | UNCHANGED
 * SWITCH_LAYOUT(UNCHANGED) | caller loads it       | SWEEP_UNLESS_EXEMPT     | UNCHANGED           | UNCHANGED
 *   = the Sym page turn    |                       |                         |                     |
 * SWEEP_BOARDS             | UNCHANGED             | SWEEP_UNLESS_EXEMPT     | UNCHANGED           | UNCHANGED
 * OPEN_BOARD               | CLEAR_PKB_SYMBOL_MODE | OPEN_EXCLUSIVE          | SHOW_UIM_IF_HIDDEN  | UNCHANGED
 * CLOSE_BOARD              | CLEAR_PKB_SYMBOL_MODE | CLOSE                   | RESTORE_STRIP *     | UNCHANGED
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
 * <p>Only the PKB symbol load raises the bar. {@code SYMBOL} and {@code PKB_SYMBOL} are the same
 * face of board −22 on two different devices but different rows here, because a PKB can load the
 * on-screen symbol keyboard too and that load must leave the bar where it was.
 *
 * <p>* {@code RESTORE_STRIP} only for {@link #CURSOR_BOARD_KEY_CODE} (−42), only when that board
 * was actually open, and only when the on-screen keyboard is visible — it undoes what OPENING the
 * cursor board did. No other board's close touches the bar.
 *
 * <p>** {@code BOARD_LOWERED}'s layout column is {@code LEAVE_EMOJI_MODE} for board −11 and
 * {@code UNCHANGED} for every other board: emoji is the one board whose "closed" also means a
 * layout-mode change, because the layout mode is where emoji's identity lives.
 *
 * <h2>{@code BOARD_OPENING} / {@code BOARD_RAISED} / {@code BOARD_LOWERED}</h2>
 *
 * <p>{@code OPEN_BOARD} and {@code CLOSE_BOARD} are for a board the funnel itself opens or closes.
 * The three rows above are for a board that is raised or lowered by <em>its own</em> mechanism and
 * only reports in — today that is the emoji board, driven from the {@code KeyboardState} machine's
 * side. {@code BOARD_OPENING} is {@code OPEN_BOARD}'s prologue (the layout column, which has to
 * run before the board takes the mode) and {@code BOARD_RAISED} is its epilogue (the bar raise and
 * the open report). Split in two because that mechanism spans the mode change, not because they
 * are different rules: put back together they are the {@code OPEN_BOARD} row.
 *
 * <h2>Two key-press policies</h2>
 *
 * <p>Both are "a key was pressed, close the boards", and they are <b>different rows with different
 * exempt sets</b> — which is deliberate, not an oversight (it was recorded as a contradiction in
 * PHASE1A-SUMMARY.md and resolved by owner ruling R3):
 *
 * <ul>
 *   <li><b>Commit-driven rebuild</b> ({@link #boardsExemptFromCommitRebuild()}) — the
 *       {@code SWITCH_LAYOUT} sweep, raised on the keyboard rebuild that follows every committed
 *       character. Spares the boards the user inputs FROM: FCC, the number pad, voice.</li>
 *   <li><b>Physical text key</b> ({@link #boardsExemptFromTextKey()}) —
 *       {@code BlackBerryIME.dismissBoardsForTextKey}, from a non-Shift, non-Enter, non-board
 *       key-down. Spares FCC and the number pad; voice does NOT survive it, because typing means
 *       the user has stopped dictating. And while emoji dynamic search is running it closes
 *       nothing at all ({@link #textKeyClosesNoBoard(boolean)}) — the key is search input.</li>
 * </ul>
 *
 * <h2>What this table deliberately does NOT express</h2>
 * <ul>
 *   <li>{@code ENTER_CURSOR_MODE} also cancels the composing word
 *       ({@code InputLogic.cancelComposingAndTouchEvent}) and {@code EXIT_CURSOR_MODE} re-requests
 *       suggestions. Those are the text pipeline, which has its own owner; the cursor column
 *       carries only the mode flag and its window.</li>
 *   <li>{@code SWITCH_LAYOUT} may also raise the slideboard ({@code SlideboardManager.show} on an
 *       alphabet load with no on-screen keyboard, {@code showNumericPanel} on a PKB symbol load).
 *       The slideboard is a separate surface with its own owner; it stays at the call site.</li>
 *   <li>The bar's two payload-carrying entry points ({@code AuxBarManager.showSuggestionStrip},
 *       {@code showAccentBar}) are bar-only moves whose transition value would have to carry
 *       {@code SuggestedWords} / the accent list, i.e. the text pipeline. They stay at their call
 *       sites; {@link BarEffect#RESTORE_STRIP_OR_UIM} is the one bar entry point with a
 *       cross-axis decision in it, and it has a row.</li>
 * </ul>
 *
 * <p>Pure logic, no Android: the whole table is exercised on the JVM by {@code CrossAxisRulesTest}.
 */
public final class CrossAxisRules {

    private CrossAxisRules() {
    }

    // ═════════════════════════════════════════════════════════ the effects

    /** What a transition does to the layout axis. */
    public enum LayoutEffect {
        UNCHANGED,
        /**
         * Leave EMOJI mode without firing UI callbacks: take the palettes down, restore the main
         * keyboard view, and put the mode back to ALPHABET.
         *
         * <p><b>Owner ruling R1, 2026-09-22:</b> leaving the emoji board returns to ALPHABET, never
         * to a PKB symbol page. The conditional {@code clearPkbSymbolMode()} this effect used to
         * carry is gone: it ran <em>after</em> the mode reset had already turned EMOJI into
         * ALPHABET, so its own {@code isInSymbolMode()} guard was false and it never fired. It
         * described a rule the code did not implement; R1 says the rule it described is not wanted
         * either.
         */
        LEAVE_EMOJI_MODE,
        /** Reset a PKB symbol mode back to the alphabet before a board takes the screen. */
        CLEAR_PKB_SYMBOL_MODE,
    }

    /** What a transition does to the board axis. */
    public enum BoardEffect {
        UNCHANGED,
        /** Sweep every open board closed — unless an exempt board is up, which spares them all. */
        CLOSE_ALL_UNLESS_EXEMPT,
        /** Sweep every open board closed, exemptions included. */
        CLOSE_ALL,
        /** Open this board, closing whatever else was open. */
        OPEN_EXCLUSIVE,
        /** Close this board. */
        CLOSE,
        /** Report this board open — it raised itself; the funnel only records it. */
        REPORT_OPENED,
        /** Report this board closed, scoped to its keycode — never "nothing is open at all". */
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
        /** Restore the suggestion strip (or the UIM, whichever this editor gets). */
        RESTORE_STRIP,
        /**
         * Put back whichever of the two the editor gets — the UIM bar when this editor uses it,
         * the suggestion strip otherwise. The bar axis' one entry point with a decision in it.
         */
        RESTORE_STRIP_OR_UIM,
    }

    /** What a transition does to the cursor axis. */
    public enum CursorEffect {
        UNCHANGED,
        ON,
        OFF,
    }

    // ═════════════════════════════════════════════════════════ the rows

    /** One row of the table. */
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
                BarEffect.UNCHANGED,          // per-layout; see barFor(transition)
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
                BarEffect.UNCHANGED,          // -42 only; see barFor(transition)
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

    /** The row for {@code transition}'s kind. Never null — every kind has a row. */
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

    /**
     * The bar effect for this exact transition — the other column whose value depends on more than
     * the kind. See the {@code *} footnote on the class table.
     */
    public static BarEffect barFor(KeyboardTransition transition) {
        switch (transition.kind()) {
            case SWITCH_LAYOUT:
                return transition.layout() == KeyboardTransition.Layout.PKB_SYMBOL
                        ? BarEffect.SHOW_UIM_IF_HIDDEN
                        : BarEffect.UNCHANGED;
            case CLOSE_BOARD:
                return transition.boardKeyCode() == CURSOR_BOARD_KEY_CODE
                        ? BarEffect.RESTORE_STRIP
                        : BarEffect.UNCHANGED;
            default:
                return ruleFor(transition).bar();
        }
    }

    // ═══════════════════════════════════════════════════ the board identities

    /**
     * The alphabet board — the default board, the one that is up when no other is. Keycode −3 is
     * already what "go back to the main keyboard" means on the UIM bar and in
     * {@code KeyboardState.onCodeInput}, so the alphabet board's identity is not a new number.
     *
     * <p>Registered as a {@code UnifiedInputBoardComponent} since Phase 1f
     * ({@code AlphabetBoardController}), so the UIM's component map is the single registry of every
     * board. It is <b>not a panel board</b> — see {@link #isPanelBoard(int)}, which is what keeps
     * the sweeps, the exclusive-open invariant and the bar's painting pass off it.
     */
    public static final int ALPHABET_BOARD_KEY_CODE = -3;

    /**
     * The symbol board, on-screen and PKB alike. Keycode −22 is the hardware Sym key's code, which
     * is already how the board is asked for. Like the alphabet board it lives in the main keyboard
     * view rather than in a panel drawn over it, and since Phase 1f it is registered
     * ({@code SymbolBoardController}) but is not a panel board.
     *
     * <p>Its page and its entry method are properties of this board; see the class table.
     */
    public static final int SYMBOL_BOARD_KEY_CODE = -22;

    /** The keyboard menu. A layout mode that opens through {@code showKeyboardMenu}. */
    public static final int MENU_BOARD_KEY_CODE = -23;

    /** Emoji board. One identity since Phase 1d: a board, opened and closed by the board rows. */
    public static final int EMOJI_KEY_CODE = -11;

    /** Clipboard board. */
    public static final int CLIPBOARD_KEY_CODE = -25;

    /** FCC / cursor board. Typed from; also the one board whose close restores the strip. */
    public static final int CURSOR_BOARD_KEY_CODE = -42;

    /** Number pad. Typed from. */
    public static final int NUMBER_PAD_KEY_CODE = -46;

    /** Voice input. Dictated from — the commit of the result is what runs the sweep. */
    public static final int VOICE_KEY_CODE = -27;

    /**
     * The inline autofill strip. Registered in the component map but not a board: it has no bar
     * toggle, it is the component every sweep spares by name, and it has never counted as open.
     */
    public static final int AUTOFILL_KEY_CODE = -37;

    /**
     * The <b>typing boards</b>: the boards that live in the main keyboard view itself rather than in
     * a panel drawn over it.
     *
     * <p>They are the boards the user types <em>on</em> — the keyboard, in other words — as opposed
     * to the panels the UIM raises above it. The distinction had no name before Phase 1f because it
     * did not need one: the typing boards were not in the UIM's component map, so "a component is
     * showing" and "a panel board is up" were accidentally the same sentence. Registering them made
     * the two come apart, and this is the list that keeps them apart.
     *
     * <p>{@link #MENU_BOARD_KEY_CODE} is also drawn in the main keyboard view, but it is not here
     * because nothing registers it: this list exists to filter the component map, and a keycode that
     * is not in the map cannot be filtered out of it.
     */
    private static final int[] TYPING_BOARDS = {
            ALPHABET_BOARD_KEY_CODE,
            SYMBOL_BOARD_KEY_CODE,
    };

    /**
     * <b>Is this board a panel?</b> — one of the two questions the UIM's component map used to be
     * asked with only one query between them (the other being "which board is up",
     * {@code KeyboardSwitcher.activeBoard()}).
     *
     * <p>A panel board is drawn over the main keyboard view and can be opened, closed and swept as a
     * unit. Everything that iterates the component map <em>meaning</em> panels asks this: the
     * sweeps, the exclusive-open invariant, the bar's painting pass and its centre-key decision, and
     * the bar-tap routing. A typing board answers false, and that is what makes it safe for an
     * always-showing board to be in the registry at all.
     */
    public static boolean isPanelBoard(int keyCode) {
        return !contains(TYPING_BOARDS, keyCode);
    }

    /** Whether {@code keyCode} is one of the {@link #TYPING_BOARDS}. */
    public static boolean isTypingBoard(int keyCode) {
        return contains(TYPING_BOARDS, keyCode);
    }

    /** The board keycode a layout value names, or {@link KeyboardTransition#NO_BOARD}. */
    public static int boardKeyCodeFor(KeyboardTransition.Layout layout) {
        switch (layout) {
            case ALPHABET:
                return ALPHABET_BOARD_KEY_CODE;
            case SYMBOL:
            case PKB_SYMBOL:
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

    // ════════════════════════════════════════ policy (a): the commit rebuild

    /**
     * The boards that survive the rebuild a committed character runs, in the order they are probed.
     *
     * <p>{@code SWITCH_LAYOUT} is raised on the rebuild that follows <em>every committed
     * character</em> ({@code InputLogic} commit → {@code applyPostEventUpdates} →
     * {@code resetKeyboardState} → {@code setAlphabetKeyboard}). A board that stays open across a
     * commit therefore has to be named here, or the user's own text closes it. FCC and the number
     * pad are boards the user TYPES from; voice is the board the user DICTATES from, and it reaches
     * the sweep by exactly the same route — the commit of the dictation result. Everything else
     * (emoji, clipboard, …) is closed by a layout change, as before.
     *
     * <p>Each exemption is an early return over the WHOLE sweep, not a per-board skip — see
     * {@link #anyExemptBoardIsUp}. That is the long-standing FCC semantics, kept deliberately.
     */
    private static final int[] BOARDS_EXEMPT_FROM_COMMIT_REBUILD = {
            CURSOR_BOARD_KEY_CODE,
            NUMBER_PAD_KEY_CODE,
            VOICE_KEY_CODE,
    };

    /** The commit-rebuild exempt keycodes, in probe order. Copied so callers cannot rewrite it. */
    public static int[] boardsExemptFromCommitRebuild() {
        return BOARDS_EXEMPT_FROM_COMMIT_REBUILD.clone();
    }

    /** Whether {@code keyCode} is a board that survives the rebuild a commit runs. */
    public static boolean boardSurvivesCommitRebuild(int keyCode) {
        return contains(BOARDS_EXEMPT_FROM_COMMIT_REBUILD, keyCode);
    }

    // ═══════════════════════════════════════ policy (b): the physical text key

    /**
     * The boards that survive a physical text key — {@code BlackBerryIME.dismissBoardsForTextKey},
     * raised from {@code KeyEventProcessor} on a non-Shift, non-Enter, non-board key-down.
     *
     * <p><b>Owner ruling R3(b), 2026-09-22.</b> This used to be FCC alone: the number pad was
     * closed by typing a letter, which is wrong for a board the user types FROM — the pad is there
     * precisely so digits and letters can be mixed. It joins FCC.
     *
     * <p>Voice is deliberately NOT here even though it survives the commit rebuild: typing means
     * the user has stopped dictating, so a text key ends the dictation. That is the one place the
     * two key-press policies differ on purpose rather than by accident.
     */
    private static final int[] BOARDS_EXEMPT_FROM_TEXT_KEY = {
            CURSOR_BOARD_KEY_CODE,
            NUMBER_PAD_KEY_CODE,
    };

    /** The text-key exempt keycodes, in probe order. Copied so callers cannot rewrite it. */
    public static int[] boardsExemptFromTextKey() {
        return BOARDS_EXEMPT_FROM_TEXT_KEY.clone();
    }

    /** Whether {@code keyCode} is a board that survives a physical text key. */
    public static boolean boardSurvivesTextKey(int keyCode) {
        return contains(BOARDS_EXEMPT_FROM_TEXT_KEY, keyCode);
    }

    /**
     * <b>Owner ruling R3(c).</b> While emoji dynamic search is running a text key is search input,
     * not typing, so it closes NO board — not even the ones the exempt set above would let go.
     * A row of the table rather than a flag buried in the dismissal.
     *
     * @param emojiDynamicSearchActive the emoji board is up AND dynamic search is enabled.
     */
    public static boolean textKeyClosesNoBoard(boolean emojiDynamicSearchActive) {
        return emojiDynamicSearchActive;
    }

    // ═════════════════════════════════════════════════════════ the probe

    /** Answers "is this board's view up", for the exemption probe. */
    public interface BoardIsUp {
        boolean test(int keyCode);
    }

    /**
     * The first commit-rebuild-exempt board that is up, or {@link KeyboardTransition#NO_BOARD}. One
     * exempt board being up spares the whole sweep, not just itself.
     */
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
