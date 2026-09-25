package dev.bbkb.ime.keyboard.inputboard;

import android.view.View;

import androidx.annotation.Nullable;

import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.keyboard.state.CrossAxisRules;

/**
 * The alphabet board's controller (keycode {@code -3}) — the default board, the one that is up when
 * no other is.
 *
 * <p>Phase 1d gave every keyboard a board <em>identity</em> but could not make the two typing boards
 * real {@link UnifiedInputBoardComponent}s, so the UIM's component map was a registry of panel
 * boards only and "every keyboard is a board" was true of the vocabulary and false of the registry.
 * Phase 1f splits the question the map was being asked — "is a PANEL board up"
 * ({@link UnifiedInputBoardManager#isPanelBoardShowing()}) versus "WHICH board is up"
 * ({@link KeyboardSwitcher#activeBoard()}) — and this class is what the first half made safe to
 * register.
 *
 * <h3>A typing board is not a panel</h3>
 *
 * <p>It has no view of its own: it <em>is</em> the main keyboard view, whose contents the keyboard
 * loaders own (the layout column of {@link CrossAxisRules}' table). So it is never shown, hidden,
 * swept or relayouted by the board framework — {@link CrossAxisRules#isPanelBoard(int)} keeps every
 * one of those paths off it, and the no-ops below are belt to that braces. What registration buys is
 * the registry: the component map now lists all eight boards, and every board keycode resolves to a
 * component that can answer for itself.
 */
public final class AlphabetBoardController extends AbstractBoardController<Void> {

    public static final int KEY_CODE = CrossAxisRules.ALPHABET_BOARD_KEY_CODE;

    public AlphabetBoardController() {
        super(KEY_CODE, null, null);
    }

    @Nullable
    private static KeyboardSwitcher switcher() {
        return KeyboardSwitcher.getInstance();
    }

    /**
     * Whether the alphabet board is the board that is up — the same answer
     * {@link KeyboardSwitcher#activeBoard()} gives, asked of one board.
     *
     * <p>A panel board wins whenever one is open, because it is drawn over the main keyboard view;
     * with nothing else up, some board is always up and it is this one. Deriving it from the single
     * canonical query rather than from a second reading of the layout mode is what makes the
     * registry's per-board answers partition {@code activeBoard()} exactly.
     */
    @Override
    public boolean isShowing() {
        final KeyboardSwitcher switcher = switcher();
        // No switcher yet: nothing has loaded a keyboard, and the alphabet is still the default.
        return switcher == null || switcher.activeBoard() == KEY_CODE;
    }

    /**
     * Always null: a typing board has no view of its own, and returning the main keyboard view here
     * would make every {@code refresh()} relayout it.
     */
    @Override
    @Nullable
    protected View peekBoardView() {
        return null;
    }

    /**
     * Deliberately nothing. The alphabet keyboard is loaded by {@code KeyboardSwitcher}'s loaders,
     * which is the layout axis; the board framework never opens a typing board (the exclusive-open
     * invariant and both sweeps are filtered to panel boards, and the bar's centre {@code -3} key
     * keeps its own dispatch branch, which restores the strip rather than "opening" anything).
     */
    @Override
    protected void onShow() {
    }

    /** Deliberately nothing — see {@link #onShow()}. A typing board is replaced, never hidden. */
    @Override
    protected void onHide() {
    }
}
