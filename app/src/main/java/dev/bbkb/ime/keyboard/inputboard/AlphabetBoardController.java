package dev.bbkb.ime.keyboard.inputboard;

import android.view.View;

import androidx.annotation.Nullable;

import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.keyboard.state.CrossAxisRules;

/**
 * The alphabet board (keycode {@code -3}), up whenever no other board is. A typing board, not a
 * panel: it is the main keyboard view, loaded by the keyboard loaders, so the board framework never
 * shows, hides, sweeps or relayouts it ({@link CrossAxisRules#isPanelBoard(int)}). It is registered
 * so every board keycode resolves to a component.
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
     * {@link KeyboardSwitcher#activeBoard()} asked of this board; false while a panel board is open
     * over it. Derived from that one query so the per-board answers partition it exactly.
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

    @Override
    protected void onShow() {
        // KeyboardSwitcher's loaders load it; the bar's -3 key restores the strip instead.
    }

    @Override
    protected void onHide() {
        // A typing board is replaced, never hidden.
    }
}
