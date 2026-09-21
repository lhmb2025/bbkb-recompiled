package dev.bbkb.ime.keyboard.inputboard.fcc;

import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.textinput.connection.RichInputConnection;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.textinput.InputMethodHelper;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.locale.ResourceLocaleUtils;
import dev.bbkb.ime.keyboard.inputboard.AbstractBoardController;
import dev.bbkb.ime.keyboard.inputboard.UnifiedBoardCoordinator;
import dev.bbkb.ime.BuildConfig;

/**
 * Controller class for the Fine Cursor Control board.
 */

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

    public FccController(BlackBerryIME blackBerryIME, Listener aVar) {
        super(KEY_CODE, blackBerryIME, aVar);
    }

    public void bindView(View view) {
        // Performance: Use lazy getter from KeyboardSwitcher instead of direct findViewById
        // FccView is now inflated from ViewStub on first access
        this.mFccView = dev.bbkb.ime.keyboard.KeyboardSwitcher.getInstance().getFccView();
        if (this.mFccView != null) {
            this.mFccView.setListeners(this, new FccView.SelectionProvider() {
                @Override // dev.bbkb.ime.keyboard.inputboard.cursor.FccView.SelectionProvider
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

    @Override // dev.bbkb.ime.keyboard.inputboard.cursor.FccView.ActionListener
    public void onMove(FccView.Direction enumC0992a) {
        switch (enumC0992a) {
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

    @Override // dev.bbkb.ime.keyboard.inputboard.cursor.FccView.ActionListener
    public void onKeyDown(int i) {
        if (i == 29) {
            this.ime.getCursorTracker().hide();
        }
        this.ime.getInputLogic().sendKeyDownWithMeta(i, 12288);
    }

    @Override // dev.bbkb.ime.keyboard.inputboard.cursor.FccView.ActionListener
    public void onKeyUp(int i) {
        this.ime.getInputLogic().sendKeyUpWithMeta(i, 12288);
    }

    @Override // dev.bbkb.ime.keyboard.inputboard.cursor.FccView.ActionListener
    public void onToggleSelect(int i) {
        if (BuildConfig.DEBUG) Log.d("FccController", "FCC selection mode toggle: direction=" + i);
        if (isViewInSelectMode()) {
            enterSelectMode();
            return;
        }
        collapseSelection(i);
        exitSelectMode();
        // REMOVED: legacy shift-state reset call - causes shift state desync when toggling FCC select mode
        // The synthetic shift up (exitSelectMode) should be sufficient to clear shift state
    }

    public boolean isViewInSelectMode() {
        return hasView() && this.mFccView.isSelectMode();
    }

    private void collapseSelection(int i) {
        int iMin;
        if (i == -1) {
            return;
        }
        RichInputConnection c0909x = this.ime.getInputLogic().mRichInputConnection;
        boolean zM5623b = ResourceLocaleUtils.isRtlLanguage(SubtypeManager.getInstance().getCurrentSubtypeLocale());
        int iM5853q = c0909x.getCursorStart();
        int iM5854r = c0909x.getCursorEnd();
        if (i != 1 || zM5623b) {
            iMin = Math.min(iM5853q, iM5854r);
        } else {
            iMin = Math.max(iM5853q, iM5854r);
        }
        c0909x.setSelection(iMin, iMin);
    }

    private void enterSelectMode() {
        this.mSelectModeActive = true;
        // Use controlMode setting to determine which shift key: 0=left shift, otherwise=right shift (default)
        // Default to right shift to avoid conflict with physical left shift key
        int i = SettingsManager.getInstance().getSettingsValues().controlMode == 0 ? 59 : 60;
        this.ime.onKeyDown(i, new KeyEvent(0L, 0L, 0, i, 0, 0, 0, 0));
    }

    private void exitSelectMode() {
        this.mSelectModeActive = false;
        // Use controlMode setting to determine which shift key: 0=left shift, otherwise=right shift (default)
        // Default to right shift to avoid conflict with physical left shift key
        int i = SettingsManager.getInstance().getSettingsValues().controlMode == 0 ? 59 : 60;
        this.ime.onKeyUp(i, new KeyEvent(0L, 0L, 1, i, 0, 0, 0, 0));
    }

    public void showFcc() {
        boolean hasView = hasView();
        boolean notShowing = hasView && !this.mFccView.isShowing();
        int keysDown = this.ime.getPhysicalKeyboardStateTracker().getNumberOfKeysDown();
        if (!hasView || !notShowing || keysDown != 0) {
            // One line, INFO, naming WHICH gate refused. Every refusal here is silent to the
            // user — the icon tap simply does nothing — so a field report is undiagnosable
            // without it. Not DEBUG: the whole point is that it survives on a release build.
            Log.i(TAG, "showFcc refused: gate="
                    + (!hasView ? "hasView" : !notShowing ? "alreadyShowing" : "keysDown")
                    + " keysDown=" + keysDown);
        }
        if (hasView && notShowing && keysDown == 0) {
            this.mFccView.setOpening(true);
            
            // REMOVED: Unstuck shift hack - no longer needed
            // The hack was sending synthetic shift events before state reset,
            // which caused timing conflicts and made the problem worse.
            
            // REMOVED: legacy shift-state reset call - this was the root cause of stuck shift!
            // Aggressively resetting ALL meta key state (shift/alt/ctrl/sym) when FCC opens
            // caused desynchronization with buffered events and external state.
            // The getNumberOfKeysDown() == 0 check already ensures no keys are held,
            // so state reset is unnecessary and harmful.
            
            this.ime.getKeyboardSwitcher().requestShiftOff();  // Unshift VKB (harmless, keeps UI consistent)
            this.mFccView.show();
            this.listener.onFccPanelShown();
            this.mFccView.setOpening(false);
        }
    }

    public void hideFcc() {
        if (hasView() && this.mFccView.isShowing()) {
            this.mFccView.setClosing(true);
            this.mFccView.hide();
            this.listener.onFccPanelHidden();
            this.ime.enableCursorMode(false);
            if (DeviceProfile.current().isPkbWithoutAlphabeticKeyboard()) {
                this.mFccView.setClosing(false);
            }
            reportClosedToCoordinator();
        }
    }

    /**
     * Tell the board coordinator, through the UIM's bookkeeping funnel, that FCC is down.
     *
     * <p>{@link UnifiedBoardCoordinator#activeBoard()} is the ONLY copy of "which board is open",
     * and it is mutated exclusively by reports. {@code UnifiedInputBoardManager.closeBoard(-42)}
     * reports; nothing else did. But FCC closes itself from a dozen places that never touch the
     * UIM — {@link #hideIfDisabled()} on a settings reload, {@link #hideUnlessToggling()},
     * {@link #onSelectionUpdate(int, int)} when the editor reports a selection, the
     * configuration-change and dismiss-keyboard paths, and the UIM's own
     * {@code updateKeyHighlightedStates} force-close — and {@code onFccPanelHidden()} does not
     * report either ({@code closeActiveComponent()} only runs the keyboard-state chain, as its
     * own javadoc says). Each of those left the coordinator claiming {@code -42} with the board
     * visibly gone, so the user's next tap on the FCC icon took {@code requestBoard}'s CLOSE
     * branch and did nothing. That is the reported "FCC sometimes does not open when tapping on
     * its icon": the tap was spent closing a board that was already closed.
     *
     * <p>Reporting from here — after the view is already down — closes that hole for every one of
     * those paths at once. It is idempotent, and it cannot recurse into another close: the sweeps
     * {@code reportBoardClosed} can reach skip components whose view is down, which FCC's now is.
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

    public void onSelectionUpdate(int i, int i2) {
        if (i != i2 && !this.ime.isMetaKeyActive()) {
            commitPendingSelection();
        }
        if (isViewActive()) {
            this.mFccView.updateButtons();
        }
    }

    public boolean consumeClosingFlag() {
        boolean z = hasView() && this.mFccView.isClosing();
        if (z) {
            this.mFccView.setClosing(false);
        }
        return z;
    }

    public void onKeyEventWhileShowing() {
        // REMOVED: legacy shift-state reset call - causes shift state corruption during normal typing
        // This was being called on every key event while FCC is open and in select mode,
        // which completely destroys shift state management.
        // No state reset is needed here - shift should work normally with FCC open.
    }

    public void hideUnlessToggling() {
        if (!isViewActive() || this.mFccView.isToggling()) {
            return;
        }
        hideFcc();
    }

    public void onFccEnabled(boolean z) {
        // REMOVED: Unstuck hack call - no longer needed
        // This was attempting to run the hack when FCC state changed,
        // but the hack has been deprecated and removed.
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

    /**
     * showFcc() carries FCC's own extra precondition — it refuses to open while any physical key
     * is held (getNumberOfKeysDown() == 0), because opening mid-chord used to strand the modifier
     * state. That gate stays in showFcc(), which is also called directly from the key path.
     */
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
