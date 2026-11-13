package dev.bbkb.ime.keyboard.inputboard.emoji;

import android.view.View;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.textinput.InputMethodHelper;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.keyboard.inputboard.AbstractBoardController;

/**
 * The emoji board's controller (keycode {@code -11}). Emoji mode lives in {@code KeyboardState},
 * since emoji is also a keyboard-layout state: this adapter asks the state machine whether it is
 * showing and toggles through {@code onEmojiKeyPressed()}, never the view. It holds no view, so it
 * registers eagerly without inflating the lazy emoji ViewStub.
 */
public final class EmojiBoardController extends AbstractBoardController<Void> {

    public static final int KEY_CODE = -11;

    public EmojiBoardController(BlackBerryIME ime) {
        super(KEY_CODE, ime, null);
    }

    private static KeyboardSwitcher switcher() {
        return KeyboardSwitcher.getInstance();
    }

    /** The state machine's emoji mode, not the view's visibility. */
    @Override
    public boolean isShowing() {
        final KeyboardSwitcher switcher = switcher();
        return switcher != null && switcher.isEmojiKeyboardShowing();
    }

    @Override
    protected View peekBoardView() {
        // Peek, never get: reporting on a never-opened board must not inflate it.
        final KeyboardSwitcher switcher = switcher();
        return switcher == null ? null : switcher.peekEmojiPalettesView();
    }

    /** Enter emoji mode through the state machine, which calls back to show the palettes. */
    @Override
    protected void onShow() {
        final KeyboardSwitcher switcher = switcher();
        if (switcher != null) {
            switcher.onEmojiKeyPressed();
        }
    }

    /**
     * Overrides the base guard instead of implementing {@link #onHide()}: when the mode flag was
     * already cleared but the palettes view is still up, the view is taken down directly.
     */
    @Override
    public void hide() {
        final KeyboardSwitcher switcher = switcher();
        if (switcher == null) {
            return;
        }
        if (switcher.isEmojiKeyboardShowing()) {
            // Via the state machine, so mode flag, palettes and main keyboard stay consistent.
            switcher.onEmojiKeyPressed();
            return;
        }
        final EmojiPalettesView view = switcher.peekEmojiPalettesView();
        if (view != null && view.isShown()) {
            switcher.hideEmojiKeyboard();
        }
    }

    /** Unused: {@link #hide()} is overridden outright. */

    @Override
    protected void onHide() {
        hide();
    }

    @Override
    public boolean isEnabled() {
        final InputMethodHelper helper = InputMethodHelper.getInstance();
        return helper != null && helper.isTextOrImMode();
    }
}
