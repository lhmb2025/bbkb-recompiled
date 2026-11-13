package dev.bbkb.ime.keyboard.inputboard;

/**
 * Single owner of "which input board is currently open." View state cannot be the source of
 * truth: a key-down side effect hides the view between a board key's down and up, so key-up would
 * reopen instead of close. Every board key goes through {@link #requestBoard(int)}; every open or
 * close path reports back through the UIM's {@code setActiveComponent}, which calls
 * {@link #notifyBoardOpened(int)} / {@link #notifyBoardClosed()}. Symbol modes stay outside.
 */
public final class UnifiedBoardCoordinator {

    /** Sentinel keycode meaning "no board is open." */
    public static final int NO_BOARD = 0;

    /**
     * Performs the visibility side effects (the UIM in production, a fake in tests). It must report
     * the result via {@link #notifyBoardOpened(int)} / {@link #notifyBoardClosed()}, so a failed
     * open leaves the coordinator truthful.
     */
    public interface BoardHost {
        /** Force-open {@code keyCode}'s board (exclusive: closes any other open board). */
        void openBoard(int keyCode);

        /** Force-close {@code keyCode}'s board and restore the normal UI. */
        void closeBoard(int keyCode);
    }

    private final BoardHost host;

    /** Open board keycode or {@link #NO_BOARD}; mutated only by the notify methods. */
    private int activeBoard = NO_BOARD;

    public UnifiedBoardCoordinator(BoardHost host) {
        this.host = host;
    }

    /** Close {@code keyCode}'s board if it is active, otherwise open it (exclusively). */
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
     * A text key was pressed: dismiss any open board. Board keys are exempted at the key-down
     * site so this cannot fight their own key-up toggle.
     */
    public void onTextKeyPressed() {
        closeBoard();
    }

    /** Reported from the UIM's {@code setActiveComponent} by every open path. */
    public void notifyBoardOpened(int keyCode) {
        activeBoard = keyCode;
    }

    /** Reported from the UIM's {@code setActiveComponent} by every close path. */
    public void notifyBoardClosed() {
        activeBoard = NO_BOARD;
    }
}
