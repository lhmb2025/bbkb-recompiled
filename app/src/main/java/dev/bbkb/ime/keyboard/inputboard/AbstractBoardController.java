package dev.bbkb.ime.keyboard.inputboard;

import android.view.View;

import androidx.annotation.Nullable;

import dev.bbkb.ime.core.BlackBerryIME;

/**
 * Shared skeleton for board controllers: keycode, IME and listener references, guarded show/hide,
 * relayout on refresh, and teardown. It never decides which board is open (that is
 * {@link UnifiedBoardCoordinator#activeBoard()}); {@link #isShowing()} only means "my view is up"
 * and each board answers it with its own predicate. show/hide are not final because voice must
 * hide unconditionally to release the microphone (its session can outlive the view).
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
     * The board's view if inflated, without inflating it. Always read it from the current holder,
     * never a cached field: boards are re-created with the input view.
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

    /**
     * Close if up, else open if enabled. Used by hardware board shortcuts while the input menu is
     * off or hidden, where there is no {@link UnifiedBoardCoordinator} toggle.
     */
    public void toggle() {
        if (isShowing()) {
            hide();
        } else if (isEnabled()) {
            show();
        }
    }

    @Override
    public void onRefresh() {
        final View view = peekBoardView();
        if (view != null && isShowing()) {
            view.requestLayout();
        }
    }

    /** Runs {@link #onDestroy()}, then drops the IME and listener references so the IME cannot leak. */
    public final void destroy() {
        onDestroy();
        this.ime = null;
        this.listener = null;
    }

    /** Subclass teardown, run before the shared references are dropped. */
    protected void onDestroy() {
        // Default: nothing to release.
    }
}
