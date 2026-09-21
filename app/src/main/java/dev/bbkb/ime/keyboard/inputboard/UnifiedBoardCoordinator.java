package dev.bbkb.ime.keyboard.inputboard;

/**
 * Single owner of "which input board is currently open."
 *
 * <p>Board visibility used to be decided ad hoc from each component's
 * {@code isShowing()} (the view's visual state). A key-down side effect
 * ({@code hideOtherComponents}) hides the view — and clears the UIM's
 * {@code activeComponent} — between a board key's down and up, so the key-up
 * handler saw "nothing open" and <em>reopened</em> the board instead of closing
 * it. On-device logging confirmed the UIM's active-component signal is cleared
 * before key-up, so it cannot be the toggle's source of truth.
 *
 * <p>This coordinator therefore owns the <em>only</em> copy of that state:
 * {@link #activeBoard}. The UIM no longer keeps a parallel activeComponent field —
 * it derives it from here, and every open/close path reports through the UIM's
 * single bookkeeping funnel ({@code setActiveComponent}), which mutates
 * {@link #activeBoard} via {@link #notifyBoardOpened(int)} /
 * {@link #notifyBoardClosed()}. This is the single idempotent toggle choke point
 * for board keys.
 *
 * <p>Drives the existing {@link UnifiedInputBoardManager} through {@link BoardHost}.
 * Every board-key entry point routes through {@link #requestBoard(int)} — physical
 * board/multifunction keys, interceptor-relayed keys, gestures, and touch on the
 * UIM bar — and the text-key dismissal is the explicit {@link #onTextKeyPressed()}
 * (board keys are exempted from it at the key-down site). Paths that still open or
 * close boards outside the coordinator (the KeyboardState emoji machine, side-effect
 * sweeps) are reconciled via {@link #notifyBoardOpened(int)} /
 * {@link #notifyBoardClosed()}. The KeyboardState-owned symbol / PKB-symbol modes
 * remain outside the coordinator for now.
 */
public final class UnifiedBoardCoordinator {

    /** Sentinel keycode meaning "no board is open." */
    public static final int NO_BOARD = 0;

    /**
     * The mechanism the coordinator drives. Backed by {@link UnifiedInputBoardManager}
     * in production and by a fake in tests, so the toggle logic can be verified
     * without Android view state. All visibility side effects live behind this.
     *
     * <p>Contract: implementations must report the resulting state back via
     * {@link #notifyBoardOpened(int)} / {@link #notifyBoardClosed()} — those calls
     * are the only thing that mutates {@link #activeBoard}, so a failed open leaves
     * the coordinator truthful instead of assuming success. Production reports
     * through {@code UnifiedInputBoardManager.setActiveComponent}.
     */
    public interface BoardHost {
        /** Force-open {@code keyCode}'s board (exclusive: closes any other open board). */
        void openBoard(int keyCode);

        /** Force-close {@code keyCode}'s board and restore the normal UI. */
        void closeBoard(int keyCode);
    }

    private final BoardHost host;

    /**
     * Authoritative "currently open board" keycode, or {@link #NO_BOARD} — the only
     * copy of this state. Mutated exclusively by {@link #notifyBoardOpened(int)} /
     * {@link #notifyBoardClosed()}, never read back from view state.
     */
    private int activeBoard = NO_BOARD;

    public UnifiedBoardCoordinator(BoardHost host) {
        this.host = host;
    }

    /**
     * Toggle a board: if it is already the active board, close it; otherwise open it
     * (the host's open is exclusive, so it closes whatever else was open). Single
     * choke point for board keys — the decision comes from {@link #activeBoard}, so a
     * second press reliably closes even though the view was hidden on the key-down
     * side of that same press.
     */
    public void requestBoard(int keyCode) {
        if (keyCode == NO_BOARD) {
            return;
        }
        if (activeBoard == keyCode) {
            host.closeBoard(keyCode);
        } else {
            host.openBoard(keyCode);
        }
    }

    /** Close the active board, if any. */
    public void closeBoard() {
        if (activeBoard != NO_BOARD) {
            host.closeBoard(activeBoard);
        }
    }

    /** Keycode of the active board, or {@link #NO_BOARD}. */
    public int activeBoard() {
        return activeBoard;
    }

    /** Whether {@code keyCode} is the active board. */
    public boolean isBoardShowing(int keyCode) {
        return keyCode != NO_BOARD && activeBoard == keyCode;
    }

    /**
     * A text key was pressed — dismiss any open board. Explicit coordinator input
     * replacing the old scattered key-down hide side effect, so the dismissal both
     * uses the per-board close semantics and keeps {@link #activeBoard} truthful
     * (no dead press on the next board key). Board keys are exempted from this at
     * the key-down site, so it cannot fight a board key's own key-up toggle.
     */
    public void onTextKeyPressed() {
        closeBoard();
    }

    /**
     * A board opened — THE mutation path for {@link #activeBoard}, reported from the
     * UIM's bookkeeping funnel (setActiveComponent) by every open path.
     */
    public void notifyBoardOpened(int keyCode) {
        activeBoard = keyCode;
    }

    /**
     * The open board closed — THE mutation path for {@link #activeBoard}, reported
     * from the UIM's bookkeeping funnel (setActiveComponent) by every close path.
     */
    public void notifyBoardClosed() {
        activeBoard = NO_BOARD;
    }
}
