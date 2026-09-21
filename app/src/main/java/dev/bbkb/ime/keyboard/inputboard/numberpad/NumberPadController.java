package dev.bbkb.ime.keyboard.inputboard.numberpad;

import android.view.View;

import java.util.Locale;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.subtypeswitcher.SubtypeFactory;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.keyboard.inputboard.AbstractBoardController;
import dev.bbkb.ime.keyboard.inputboard.BoardKeyboardFactory;

/**
 * Controller for the Number Pad input board: builds the 7x4 math/numeric keyboard
 * (element numberPad, keyboard_layout_set_number_pad.xml) and shows it over the main
 * keyboard, toggled from the UIM bar. Registered in UnifiedInputBoardManager's
 * componentMap, it is dispatched entirely through the generic board branch.
 *
 * <p>The reference {@link AbstractBoardController} subclass (§5.6 item 1): everything left here is
 * number-pad-specific — the keyboard build and its locale-keyed cache — and the board skeleton is
 * in the base class.
 */
public class NumberPadController extends AbstractBoardController<NumberPadController.Listener> {

    private static final String TAG = "NumberPadController";

    public static final int KEY_CODE = -46;

    private static final int NUMBER_PAD_ELEMENT_ID = 42;

    private Keyboard mKeyboard;

    private Locale mKeyboardLocale;

    public interface Listener {
        void onNumberPadShown();

        void onNumberPadHidden();
    }

    public NumberPadController(BlackBerryIME blackBerryIME, Listener listener) {
        super(KEY_CODE, blackBerryIME, listener);
    }

    /**
     * Always fetched through KeyboardSwitcher (which lazily inflates the ViewStub and
     * nulls its reference on input-view recreation) so this controller never holds a
     * stale view across forceRecreateInputView().
     */
    private NumberPadView getView() {
        return KeyboardSwitcher.getInstance().getNumberPadView();
    }

    @Override
    protected View peekBoardView() {
        return KeyboardSwitcher.getInstance().peekNumberPadView();
    }

    private Keyboard getOrBuildKeyboard() {
        Locale locale = SubtypeManager.getInstance().getCurrentSubtypeLocale();
        if (mKeyboard == null || !locale.equals(mKeyboardLocale)) {
            mKeyboard = BoardKeyboardFactory.buildBoardKeyboard(
                    this.ime,
                    SubtypeFactory.createSubtype(locale.toString(), "number_pad"),
                    NUMBER_PAD_ELEMENT_ID);
            mKeyboardLocale = locale;
        }
        return mKeyboard;
    }

    /** Theme or keyboard-height change: drop the built keyboard so the next show rebuilds. */
    public void invalidateKeyboard() {
        mKeyboard = null;
        mKeyboardLocale = null;
    }

    @Override
    protected void onShow() {
        NumberPadView view = getView();
        if (view == null || view.isShowing()) {
            return;
        }
        try {
            view.setKeyboard(getOrBuildKeyboard());
        } catch (RuntimeException e) {
            Logger.error(TAG, "Failed to build number pad keyboard: " + e.getMessage());
            return;
        }
        // Listener BEFORE show, like ClipboardController: onNumberPadShown() runs the
        // requestShiftOff keyboard-state chain, whose board sweep would hide a view that
        // is already visible.
        this.listener.onNumberPadShown();
        view.show();
    }

    @Override
    protected void onHide() {
        NumberPadView view = KeyboardSwitcher.getInstance().peekNumberPadView();
        if (view != null && view.isShowing()) {
            view.hide();
            this.listener.onNumberPadHidden();
        }
    }

    @Override
    public boolean isShowing() {
        NumberPadView view = KeyboardSwitcher.getInstance().peekNumberPadView();
        return view != null && view.isShowing();
    }

    @Override
    protected void onDestroy() {
        this.mKeyboard = null;
        this.mKeyboardLocale = null;
    }
}
