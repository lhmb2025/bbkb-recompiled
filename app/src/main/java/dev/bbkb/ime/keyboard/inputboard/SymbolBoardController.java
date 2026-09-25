package dev.bbkb.ime.keyboard.inputboard;

import android.view.View;

import androidx.annotation.Nullable;

import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.keyboard.state.CrossAxisRules;

/**
 * The symbol board's controller (keycode {@code -22}), on-screen and PKB alike.
 *
 * <p>Keycode −22 is the hardware Sym key's code, which is already how the board is asked for. Like
 * the alphabet board it lives in the main keyboard view rather than in a panel drawn over it, so
 * everything {@link AlphabetBoardController} says about a typing board not being a panel applies
 * here too: no view of its own, no show, no hide, no sweep, no relayout.
 *
 * <h3>The page and the entry method stay properties of this board</h3>
 *
 * <p>Which face of −22 is showing (the page) and how it was opened (the entry method: 1 = Sym key,
 * 2 = PKB on-screen SYM, 3 = VKB SYM, 0 = spent) are not axes of their own and do not move here.
 * They are state of this board, and this board's state machine is {@code KeyboardState} — which is
 * where they live, where the only reads of them are, and where they stay. This class is the adapter
 * that lets the board registry name the board; it is not a second home for the board's state.
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

    /**
     * Whether the symbol board is the board that is up — {@link KeyboardSwitcher#activeBoard()}
     * asked of one board. False while a panel board is open over it, for the reason given on
     * {@link AlphabetBoardController#isShowing()}.
     */
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

    /**
     * Deliberately nothing. The symbol keyboard is loaded by {@code KeyboardSwitcher}'s
     * {@code setVkbSymbolsKeyboard} / {@code setPkbSymbolsKeyboard}, driven by the
     * {@code KeyboardState} machine — the layout axis, not the board framework.
     */
    @Override
    protected void onShow() {
    }

    /** Deliberately nothing — see {@link #onShow()}. */
    @Override
    protected void onHide() {
    }
}
