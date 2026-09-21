package dev.bbkb.ime.keyboard.inputboard.clipboard;

import android.view.View;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.textinput.InputMethodHelper;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.keyboard.inputboard.AbstractBoardController;


public class ClipboardController extends AbstractBoardController<ClipboardController.Listener>
        implements ClipboardView.OnPasteListener {

    private static final String TAG = "ClipboardController";

    public static final int KEY_CODE = -25;


    private ClipboardView mClipboardView;

    private ClipboardHistoryManager mHistoryManager;

    
    public interface Listener {
        void onClipboardShown();

        void onClipboardHidden();
    }

    public ClipboardController(BlackBerryIME blackBerryIME, Listener aVar) {
        super(KEY_CODE, blackBerryIME, aVar);
        this.mHistoryManager = new ClipboardHistoryManager(blackBerryIME);
    }

    public void bindView(View view) {
        ClipboardView clipboardView = this.mClipboardView;
        if (clipboardView != null) {
            clipboardView.unregisteredListener();
            this.mClipboardView.setListener(null);
        }
        this.mClipboardView = (ClipboardView) view.findViewById(R.id.inputboard_clipboard_view);
        ClipboardView clipboardView2 = this.mClipboardView;
        if (clipboardView2 != null) {
            clipboardView2.setListener(this);
            this.mClipboardView.initialize(this.mHistoryManager, this.ime);
        }
    }

    public void dismiss() {
        hideClipboard();
    }

    @Override
    protected void onDestroy() {
        if (hasView()) {
            this.mClipboardView.unregisteredListener();
            this.mClipboardView.setListener(null);
            this.mClipboardView.release();
            this.mClipboardView = null;
        }
        if (hasHistoryManager()) {
            this.mHistoryManager.release();
        }
    }

    @Override
    protected View peekBoardView() {
        return this.mClipboardView;
    }

    private boolean hasHistoryManager() {
        return this.mHistoryManager != null;
    }

    public boolean hasView() {
        return this.mClipboardView != null;
    }

    public boolean isClipboardShowing() {
        return hasView() && (this.mClipboardView.isVisible() || this.mClipboardView.isOpening());
    }

    public void showClipboard() {
        if (!hasView() || this.mClipboardView.isVisible()) {
            return;
        }
        Logger.debug(TAG, "Showing Clipboard");
        this.mClipboardView.setOpening(true);
        this.listener.onClipboardShown();
        this.mClipboardView.show();
        this.mClipboardView.setOpening(false);
        this.mClipboardView.getContext();
    }

    public void hideClipboard() {
        if (hasView() && this.mClipboardView.isVisible()) {
            Logger.debug(TAG, "Hiding Clipboard");
            this.mClipboardView.hide();
            this.listener.onClipboardHidden();
        }
    }

    @Override // dev.bbkb.ime.keyboard.inputboard.clipboard.ClipboardView.OnPasteListener
    public void onPaste() {
        this.ime.getInputLogic().pasteFromClipboard();
    }

    @Override
    protected void onHide() {
        hideClipboard();
    }

    @Override
    protected void onShow() {
        showClipboard();
    }

    @Override
    public boolean isShowing() {
        return isClipboardShowing();
    }

    @Override
    public boolean isEnabled() {
        InputMethodHelper inputMethodHelper = InputMethodHelper.getInstance();
        return inputMethodHelper != null && inputMethodHelper.isDeviceUnlocked();
    }
}
