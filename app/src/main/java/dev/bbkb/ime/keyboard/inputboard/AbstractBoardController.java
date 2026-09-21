package dev.bbkb.ime.keyboard.inputboard;

import android.view.View;

import androidx.annotation.Nullable;

import dev.bbkb.ime.core.BlackBerryIME;

/**
 * The skeleton every input board controller was writing out for itself.
 *
 * <p>{@link UnifiedInputBoardComponent} is a real abstraction, but with no base class behind it the
 * clipboard, FCC, voice and number-pad controllers each re-implemented the same five things: a
 * constant keycode, a back-reference to the IME and a one-method-pair {@code Listener}, a
 * {@code show}/{@code hide} pair guarded on "am I already in that state", an {@code onRefresh} that
 * relayouts the view when it is up, and a {@code destroy()} that nulls the lot. Between
 * {@code ClipboardController} and {@code NumberPadController} that was ~75% of both files (§3.6).
 *
 * <h3>What this class does not do</h3>
 *
 * <p>It does <strong>not</strong> decide, cache, or infer which board is open. That state has
 * exactly one owner, {@link UnifiedBoardCoordinator#activeBoard()}, and it has one owner because
 * deriving it from clobberable view state produced a reproducible on-device bug: a key-down side
 * effect hid the view between a board key's down and up, so the key-up handler saw "nothing open"
 * and reopened the board instead of closing it. {@link #isShowing()} stays abstract for that
 * reason — it answers "is my view up right now", which is a different question from "is my board
 * the active board", and each board answers it with its own view's own predicate (several of them
 * have an in-between opening/closing state). Nothing here reads {@code getVisibility()}.
 *
 * <h3>Template methods</h3>
 *
 * <p>{@link #show()} and {@link #hide()} apply the shared guard and then call {@link #onShow()} /
 * {@link #onHide()}. Both are overridable rather than final because voice legitimately breaks the
 * pattern: a recognizer session can outlive its view, so its {@code hide()} must run
 * unconditionally to release the microphone (§5.6 item 5 leaves that asymmetry alone).
 */
public abstract class AbstractBoardController<L> implements UnifiedInputBoardComponent {

    private final int keyCode;

    /** The host IME. Nulled by {@link #destroy()}; subclasses must tolerate null after that. */
    @Nullable
    protected BlackBerryIME ime;

    /** The shown/hidden callback pair. Nulled by {@link #destroy()}. */
    @Nullable
    protected L listener;

    protected AbstractBoardController(int keyCode, BlackBerryIME ime, L listener) {
        this.keyCode = keyCode;
        this.ime = ime;
        this.listener = listener;
    }

    @Override
    public final int getKeyCode() {
        return keyCode;
    }

    /** Boards are available unless the subclass has a reason to say otherwise. */
    @Override
    public boolean isEnabled() {
        return true;
    }

    /**
     * The board's view if it has been inflated, without inflating it. Boards live behind a
     * {@code ViewStub} and are re-created with the input view, so this must go back to the current
     * holder (usually {@code KeyboardSwitcher}) rather than return a cached field that can outlive
     * a {@code forceRecreateInputView()}.
     */
    @Nullable
    protected abstract View peekBoardView();

    /** Show the board. Called only when {@link #isShowing()} was false. */
    protected abstract void onShow();

    /** Hide the board. Called only when {@link #isShowing()} was true. */
    protected abstract void onHide();

    @Override
    public void show() {
        if (isShowing()) {
            return;
        }
        onShow();
    }

    @Override
    public void hide() {
        if (!isShowing()) {
            return;
        }
        onHide();
    }

    @Override
    public void onRefresh() {
        final View view = peekBoardView();
        if (view != null && isShowing()) {
            view.requestLayout();
        }
    }

    /**
     * Release the board. Subclasses override {@link #onDestroy()} for their own teardown; this
     * drops the IME and listener references afterwards, which is the part that was identical
     * everywhere and the part that leaks the IME if it is forgotten.
     */
    public final void destroy() {
        onDestroy();
        this.ime = null;
        this.listener = null;
    }

    /** Subclass teardown, run before the shared references are dropped. */
    protected void onDestroy() {
    }
}
