package dev.bbkb.ime.keyboard.inputboard.fcc;

import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.textinput.connection.RichInputConnection;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.textinput.InputMethodHelper;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.locale.ResourceLocaleUtils;
import dev.bbkb.ime.keyboard.inputboard.AbstractBoardController;
import dev.bbkb.ime.keyboard.inputboard.UnifiedBoardCoordinator;
import dev.bbkb.ime.BuildConfig;

/** The Fine Cursor Control board. */

public class FccController extends AbstractBoardController<FccController.Listener>
        implements FccView.ActionListener {

    private static final String TAG = "FccController";

    public static final int KEY_CODE = -42;

    private FccView mFccView;

    private boolean mSelectModeActive = false;

    
    public interface Listener {
        void onFccPanelShown();

        void onFccPanelHidden();
    }

    /** @param listener receives panel shown and hidden events */
    public FccController(BlackBerryIME blackBerryIME, Listener listener) {
        super(KEY_CODE, blackBerryIME, listener);
    }

    public void bindView(View view) {
        // Inflated from a ViewStub on first access.
        this.mFccView = dev.bbkb.ime.keyboard.KeyboardSwitcher.getInstance().getFccView();
        if (this.mFccView != null) {
            this.mFccView.setListeners(this, new FccView.SelectionProvider() {
                @Override
                public boolean hasSelection() {
                    return FccController.this.ime.getInputLogic().mRichInputConnection.hasSelection();
                }
            });
        }
    }

    public boolean hasView() {
        return this.mFccView != null;
    }

    public boolean isViewActive() {
        return hasView() && (this.mFccView.isShowing() || this.mFccView.isOpening());
    }

    /** @param direction the arrow pressed */
    @Override
    public void onMove(FccView.Direction direction) {
        switch (direction) {
            case LEFT:
                this.ime.moveLeft(1);
                break;
            case RIGHT:
                this.ime.moveRight(1);
                break;
            case UP:
                this.ime.moveUp(1);
                break;
            case DOWN:
                this.ime.moveDown(1);
                break;
        }
    }

    /** @param keyCode the key code */
    @Override
    public void onKeyDown(int keyCode) {
        if (keyCode == 29) {
            this.ime.getCursorTracker().hide();
        }
        this.ime.getInputLogic().sendKeyDownWithMeta(keyCode, 12288);
    }

    /** @param keyCode the key code */
    @Override
    public void onKeyUp(int keyCode) {
        this.ime.getInputLogic().sendKeyUpWithMeta(keyCode, 12288);
    }

    /** @param collapseDirection how to collapse a selection on exit: 1 to its end, 0 to its start, -1 leave it */
    @Override
    public void onToggleSelect(int collapseDirection) {
        if (BuildConfig.DEBUG) Log.d("FccController", "FCC selection mode toggle: direction=" + collapseDirection);
        if (isViewInSelectMode()) {
            enterSelectMode();
            return;
        }
        collapseSelection(collapseDirection);
        exitSelectMode();
        // No shift-state reset: the synthetic shift up clears it, and a reset desyncs shift.
    }

    public boolean isViewInSelectMode() {
        return hasView() && this.mFccView.isSelectMode();
    }

    /** @param collapseDirection 1 to collapse to the end (start in RTL), 0 to the start, -1 to leave the selection */
    private void collapseSelection(int collapseDirection) {
        int iMin;
        if (collapseDirection == -1) {
            return;
        }
        RichInputConnection c0909x = this.ime.getInputLogic().mRichInputConnection;
        boolean zM5623b = ResourceLocaleUtils.isRtlLanguage(SubtypeManager.getInstance().getCurrentSubtypeLocale());
        int iM5853q = c0909x.getCursorStart();
        int iM5854r = c0909x.getCursorEnd();
        if (collapseDirection != 1 || zM5623b) {
            iMin = Math.min(iM5853q, iM5854r);
        } else {
            iMin = Math.max(iM5853q, iM5854r);
        }
        c0909x.setSelection(iMin, iMin);
    }

    private void enterSelectMode() {
        this.mSelectModeActive = true;
        // controlMode 0 = left shift, else right shift (default, so the physical left shift is free).
        int i = SettingsManager.getInstance().getSettingsValues().controlMode == 0 ? 59 : 60;
        this.ime.onKeyDown(i, new KeyEvent(0L, 0L, 0, i, 0, 0, 0, 0));
    }

    private void exitSelectMode() {
        this.mSelectModeActive = false;
        int i = SettingsManager.getInstance().getSettingsValues().controlMode == 0 ? 59 : 60;
        this.ime.onKeyUp(i, new KeyEvent(0L, 0L, 1, i, 0, 0, 0, 0));
    }

    public void showFcc() {
        boolean hasView = hasView();
        boolean notShowing = hasView && !this.mFccView.isShowing();
        int keysDown = this.ime.getPhysicalKeyboardStateTracker().getNumberOfKeysDown();
        if (!hasView || !notShowing || keysDown != 0) {
            // INFO, not DEBUG, so release builds log which gate refused: refusals are silent to the user.
            Log.i(TAG, "showFcc refused: gate="
                    + (!hasView ? "hasView" : !notShowing ? "alreadyShowing" : "keysDown")
                    + " keysDown=" + keysDown);
        }
        if (hasView && notShowing && keysDown == 0) {
            this.mFccView.setOpening(true);

            // Do not reset meta state here: it desyncs with buffered events (stuck shift), and
            // the keysDown check already guarantees no key is held.

            this.ime.getKeyboardSwitcher().requestShiftOff();
            this.mFccView.show();
            this.listener.onFccPanelShown();
            this.mFccView.setOpening(false);
        }
    }

    public void hideFcc() {
        if (hasView() && this.mFccView.isShowing()) {
            this.mFccView.hide();
            this.listener.onFccPanelHidden();
            this.ime.enableCursorMode(false);
            reportClosedToCoordinator();
        }
    }

    /**
     * Tell the board coordinator FCC is down. FCC closes itself from many paths that bypass the UIM,
     * and a coordinator still claiming {@code -42} turns the next icon tap into a close. Idempotent,
     * and cannot recurse: {@code reportBoardClosed}'s sweeps skip components whose view is down.
     */
    private void reportClosedToCoordinator() {
        BlackBerryIME ime = this.ime;
        if (ime == null) {
            return;
        }
        dev.bbkb.ime.keyboard.KeyboardSwitcher switcher = ime.getKeyboardSwitcher();
        dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardManager uim =
                switcher != null ? switcher.getUnifiedInputBoardManager() : null;
        if (uim != null) {
            uim.reportBoardClosed(KEY_CODE);
        }
    }

    @Override
    protected void onDestroy() {
        if (hasView()) {
            this.mFccView.setListeners((FccView.ActionListener) null, (FccView.SelectionProvider) null);
            this.mFccView = null;
        }
    }

    @Override
    protected View peekBoardView() {
        return this.mFccView;
    }

    public void dismiss() {
        hideFcc();
    }

    private void commitPendingSelection() {
        if (isViewActive()) {
            hideFcc();
        }
    }

    /**
     * @param newSelStart the new selection start
     * @param newSelEnd the new selection end
     */
    public void onSelectionUpdate(int newSelStart, int newSelEnd) {
        if (newSelStart != newSelEnd && !this.ime.isMetaKeyActive()) {
            commitPendingSelection();
        }
        if (isViewActive()) {
            this.mFccView.updateButtons();
        }
    }

    public void onKeyEventWhileShowing() {
        // Nothing to do: resetting shift state here would corrupt it while typing.
    }

    public void hideUnlessToggling() {
        if (!isViewActive() || this.mFccView.isToggling()) {
            return;
        }
        hideFcc();
    }

    /** @param forced true if FCC was enabled */
    public void onFccEnabled(boolean forced) {
        // Nothing to do when the setting changes.
    }

    public void hideIfDisabled() {
        if (!isViewActive() || isEnabled()) {
            return;
        }
        hideFcc();
    }

    public boolean isSelectModeActive() {
        return this.mSelectModeActive;
    }

    @Override
    protected void onHide() {
        hideFcc();
    }

    /** showFcc() refuses while any physical key is held (opening mid-chord strands modifier state). */
    @Override
    protected void onShow() {
        showFcc();
    }

    @Override
    public boolean isShowing() {
        return isViewActive();
    }

    @Override
    public boolean isEnabled() {
        InputMethodHelper inputMethodHelper = InputMethodHelper.getInstance();
        return inputMethodHelper != null && inputMethodHelper.isPortraitNonPasswordField();
    }
}
