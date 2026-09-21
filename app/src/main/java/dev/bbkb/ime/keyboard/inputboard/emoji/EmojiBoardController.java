package dev.bbkb.ime.keyboard.inputboard.emoji;

import android.view.View;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.textinput.InputMethodHelper;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.keyboard.inputboard.AbstractBoardController;

/**
 * The emoji board's controller (keycode {@code -11}).
 *
 * <p>Emoji was the outlier of the five boards: there was no controller, so
 * {@code EmojiPalettesView} implemented {@link
 * dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardComponent} itself — a view that
 * was also a board component — and its {@code show()}/{@code hide()} turned round and called
 * {@code KeyboardSwitcher}. That indirection had to be special-cased in <em>both</em>
 * {@code UnifiedInputBoardManager.dispatchBoardAction} and {@code closeBoard}, because the view's
 * "am I showing" (is the pager laid out and visible) is not the same question as the board's
 * (is the IME in emoji mode). §5.6 item 4.
 *
 * <p><strong>Emoji mode still lives in {@code KeyboardState}</strong> and this controller does not
 * try to take it. That is the point: the state machine owns the mode because emoji is also a
 * keyboard-layout state, and this class is the adapter that lets the board framework talk to it.
 * So {@link #isShowing()} asks the state machine, and both {@link #onShow()} and {@link #hide()}
 * drive the toggle through {@code onEmojiKeyPressed()} rather than poking the view. The
 * {@code showEmojiKeyboard()} / {@code hideEmojiKeyboard()} pair the state machine calls back into
 * is unchanged.
 *
 * <p>Because it holds no view, this controller can be registered eagerly with the rest, which also
 * removes the reason {@code registerComponents()} had to peek at the emoji view: the lazy
 * {@code ViewStub} inflation from IB-1 stays exactly as it is, and nothing has to inflate a board
 * just to say it is closed.
 */
public final class EmojiBoardController extends AbstractBoardController<Void> {

    public static final int KEY_CODE = -11;

    public EmojiBoardController(BlackBerryIME ime) {
        super(KEY_CODE, ime, null);
    }

    private static KeyboardSwitcher switcher() {
        return KeyboardSwitcher.getInstance();
    }

    /**
     * The state machine's emoji mode, not the view's visibility. This is what the two removed
     * special cases disagreed about: {@code closeBoard} already asked the state machine, while
     * {@code dispatchBoardAction} asked the view.
     */
    @Override
    public boolean isShowing() {
        final KeyboardSwitcher switcher = switcher();
        return switcher != null && switcher.isEmojiKeyboardShowing();
    }

    @Override
    protected View peekBoardView() {
        // peek, never get: reporting on a board that was never opened must not inflate it (IB-1).
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
     * Overrides the {@link AbstractBoardController} guard rather than implementing
     * {@link #onHide()}, so that the second half of the old {@code closeBoard} special case
     * survives: if the mode flag has already been cleared by a side effect but the palettes view
     * is still up, take the view down directly. The guard would have skipped that case, since it
     * is by definition "not showing" as far as the state machine is concerned.
     */
    @Override
    public void hide() {
        final KeyboardSwitcher switcher = switcher();
        if (switcher == null) {
            return;
        }
        if (switcher.isEmojiKeyboardShowing()) {
            // Exit via the state machine so the mode flag, the palettes view and the main
            // keyboard's visibility all stay consistent.
            switcher.onEmojiKeyPressed();
            return;
        }
        final EmojiPalettesView view = switcher.peekEmojiPalettesView();
        if (view != null && view.isShown()) {
            switcher.hideEmojiKeyboard();
        }
    }

    /** Unused: {@link #hide()} is overridden outright, for the reason documented on it. */
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
