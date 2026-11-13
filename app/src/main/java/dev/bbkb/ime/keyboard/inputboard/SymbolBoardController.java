package dev.bbkb.ime.keyboard.inputboard;

import android.view.View;

import androidx.annotation.Nullable;

import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.keyboard.state.CrossAxisRules;

/**
 * The symbol board (keycode {@code -22}, the hardware Sym key's code), on-screen and PKB alike.
 * A typing board like {@link AlphabetBoardController}: no view, show, hide, sweep or relayout.
 * Its page and entry method (1 = Sym key, 2 = PKB on-screen SYM, 3 = VKB SYM, 0 = spent) live in
 * {@code KeyboardState}; this class only lets the registry name the board.
 */
public final class SymbolBoardController extends AbstractBoardController<Void> {

    public static final int KEY_CODE = CrossAxisRules.SYMBOL_BOARD_KEY_CODE;

    public SymbolBoardController() {
        super(KEY_CODE, null, null);
    }

    @Nullable
    private static KeyboardSwitcher switcher() {
        return KeyboardSwitcher.getInstance();
    }

    /** {@link KeyboardSwitcher#activeBoard()} asked of this board; false under an open panel board. */
    @Override
    public boolean isShowing() {
        final KeyboardSwitcher switcher = switcher();
        return switcher != null && switcher.activeBoard() == KEY_CODE;
    }

    /** Always null: a typing board has no view of its own. */
    @Override
    @Nullable
    protected View peekBoardView() {
        return null;
    }

    @Override
    protected void onShow() {
        // Loaded by KeyboardSwitcher's set*SymbolsKeyboard, driven by KeyboardState.
    }

    @Override
    protected void onHide() {
        // A typing board is replaced, never hidden.
    }
}
