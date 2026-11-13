package dev.bbkb.ime.core.textinput;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputBinding;

import dev.bbkb.ime.core.locale.RichInputMethodManager;
import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.personaldictionary.DictionaryManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities;
import dev.bbkb.ime.core.keyevent.InputSource;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.keyboard.MainKeyboardView;
import dev.bbkb.ime.keyboard.auxbar.AuxBarManager;
import dev.bbkb.ime.keyboard.auxbar.suggestions.CJKSuggestionGridView;

import java.util.Locale;
import dev.bbkb.ime.BuildConfig;

/** BlackBerryIME's onStartInput / onStartInputView / onFinishInputView / onFinishInput bodies. */
public class InputSessionCoordinator {

    private static final String TAG = "InputSessionCoord";

    private final BlackBerryIME ime;

    public InputSessionCoordinator(BlackBerryIME ime) {
        this.ime = ime;
    }

    public void onStartInputInternal(EditorInfo editorInfo, boolean restarting) {
        Logger.debug(TAG, "onStartInputInternal(): restarting=" + restarting);
        // A key-raised input menu belonged to the last field. Hide it through the menu manager
        // (keeps board bookkeeping true) and before BlackBerryIME.onStartInput's board sweep.
        if (SettingsValues.isInputMenuRequestedByKey()) {
            ime.getUiCoordinator().hideUnifiedInputBoard();
            SettingsValues.setInputMenuRequestedByKey(false);
        }
        // A hamburger-raised menu too: the new field starts from its own bar (AuxBarChoice).
        if (ime.getUiCoordinator() != null) {
            ime.getUiCoordinator().onMenuTakenDown();
        }
        ime.superOnStartInput(editorInfo, restarting);
        if (shouldReloadKeyboard(editorInfo, restarting)) {
            ime.setNeedsKeyboardReload(true);
        }
    }

    public void onStartInputViewInternal(EditorInfo editorInfo, boolean restarting) throws Resources.NotFoundException {
        Logger.debug(TAG, "onStartInputViewInternal(): restarting=" + restarting);
        boolean z2 = false;
        ime.setNeedsKeyboardReload(false);
        ime.superOnStartInputView(editorInfo, restarting);
        // Do not clear the IME caches per focus (a dozen binder calls); they are invalidated on
        // change. This only catches a system locale change and ages them out.
        ime.getRichInputMethodManager().onInputViewStarted();
        InputMethodHelper.onEditorStarted(editorInfo);
        KeyboardSwitcher ks = ime.getKeyboardSwitcher();
        MainKeyboardView mainKeyboardView = ks.getMainKeyboardView();
        SettingsValues settings = ime.getSettingsManager().getSettingsValues();
        if (editorInfo == null) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Null EditorInfo in onStartInputView()");
            return;
        }
        Logger.info(TAG, "Starting input. Cursor position = " + editorInfo.initialSelStart + "," + editorInfo.initialSelEnd);
        if (EditorCapabilities.hasPrivateImeOption(null, "nm", editorInfo)) {
            Logger.warn(TAG, "Deprecated private IME option specified: " + editorInfo.privateImeOptions);
            Logger.warn(TAG, "Use " + ime.getPackageName() + ".noMicrophoneKey instead");
        }
        if (EditorCapabilities.hasPrivateImeOption(ime.getPackageName(), "forceAscii", editorInfo)) {
            Logger.warn(TAG, "Deprecated private IME option specified: " + editorInfo.privateImeOptions);
            Logger.warn(TAG, "Use EditorInfo.IME_FLAG_FORCE_ASCII flag instead");
        }
        if (mainKeyboardView == null) {
            return;
        }
        mainKeyboardView.setGestureDetector(ime.getOrCreateGestureDetector());
        // A keyboard removed on the Language screen while it was the active one stays the
        // framework's current subtype; move to an enabled one before anything reads it.
        if (RichInputMethodManager.isInitialized()) {
            RichInputMethodManager.getInstance().moveToEnabledSubtypeIfCurrentIsNot(ime);
        }
        if (ime.getSubtypeManager().shouldRemoveActiveKeyboardLocale(ime, Resources.getSystem().getConfiguration().getLocales())) {
            ime.updateSuggestionsFromSubtype(InputSource.INTERNAL);
        }
        boolean shouldInit = shouldReloadKeyboard(editorInfo, restarting);
        if (shouldInit) {
            ime.setShouldShowOnFirstKeypress(true);
            ime.setWasBackPressed(false);
            ime.getSubtypeManager().updateParametersOnStartInputView();
        }
        ime.updateFullscreenMode();
        InputLogic inputLogic = ime.getInputLogic();
        if (!settings.hasHardwareKeyboard || DeviceProfile.current().isPkbWithoutAlphabeticKeyboard()) {
            inputLogic.resetInputState(ime.getSubtypeManager().getCurrentConverterDescriptor(), settings);
            Locale locale = ime.getSubtypeManager().getCurrentSubtypeLocale();
            if (locale != null && !locale.equals(inputLogic.mSuggestionEngine.getLocale())) {
                ime.reloadDictionaryForSubtype();
            }
            inputLogic.updateDynamicLearningState();
            ime.getContactsLearningManager().requestContactsPermissionIfNeeded(editorInfo);
            if (!inputLogic.mRichInputConnection.resetConnection(editorInfo.initialSelStart, editorInfo.initialSelEnd, true)) {
                ime.getUiUpdateHandler().postUpdateSwitchKeyboard(shouldInit, 5);
                z2 = true;
            } else {
                inputLogic.mRichInputConnection.recalibrateCursorPosition();
                ime.getUiUpdateHandler().postUpdateShiftState(true, true);
            }
        } else {
            inputLogic.clearComposingText(true);
            // This branch skips resetInputState, so zero mCommitType here: a leftover 4 gives the
            // new field's first word an unearned auto-space.
            inputLogic.mCommitType = 0;
            if (!inputLogic.mRichInputConnection.resetConnection(editorInfo.initialSelStart, editorInfo.initialSelEnd, true)) {
                ime.getUiUpdateHandler().postUpdateSwitchKeyboard(shouldInit, 5);
                z2 = true;
            } else {
                inputLogic.mRichInputConnection.recalibrateCursorPosition();
                ime.getUiUpdateHandler().postUpdateShiftState(true, true);
            }
            // Needed per field (a password field must not leave learning off); cheap when the
            // input type is unchanged.
            inputLogic.updateDynamicLearningState();
            // resetInputState normally requests this; without it the cursor tracker, commit
            // indicator and add-to-dictionary get no cursor-anchor updates.
            inputLogic.mRichInputConnection.requestCursorUpdates(true, true);
        }
        SettingsValues updatedSettings = ime.getSettingsManager().getSettingsValues();
        if (shouldInit) {
            mainKeyboardView.closing();
            // Load this editor's settings before building the keyboard (the mic key reads them).
            ime.loadSettings();
            updatedSettings = ime.getSettingsManager().getSettingsValues();
            ime.applyAutoCorrectionSettings(updatedSettings);
            ks.startInput(editorInfo, ime.getCurrentInputType(), ime.getCurrentImeOptions());

            AuxBarManager auxBarManager = ime.getAuxBarManager();
            if (auxBarManager != null && ks.getKeyboardBuilder() != null) {
                auxBarManager.setCurrentLocale(ime.getSubtypeManager().getCurrentSubtypeLocale());
            }

            if (z2) {
                ks.saveKeyboardState(editorInfo.inputType);
            }
            if (updatedSettings.editorCapabilities.shouldShowSuggestions) {
                ime.getLanguagePackLocaleMonitor().forceBootComplete(ime);
            }
        } else if (restarting) {
            ime.getUiCoordinator().hideUnifiedInputBoard();
            ks.onBackspaceInSymbolMode(ime.getCurrentInputType(), ime.getCurrentImeOptions());
            ime.resetKeyboardState();
        }
        ime.getUiUpdateHandler().cancelPendingSuggestionUpdates();
        mainKeyboardView.setMainDictionaryAvailability(ime.getDictionaryLoader().isDictionaryReady());
        mainKeyboardView.setKeyPreviewPopupEnabled(updatedSettings.isKeyPreviewPopupEnabled, updatedSettings.keyPreviewPopupDismissDelay);
        mainKeyboardView.setSlidingKeyInputPreviewEnabled(updatedSettings.isSlidingKeyInputPreviewEnabled);
        mainKeyboardView.setVkbGestureHandlingEnabledByUser(updatedSettings.isVkbGestureInputEnabledForLocale());
        mainKeyboardView.setCkbGestureHandlingEnabledByUser(updatedSettings.isCkbGestureInputEnabledForLocale());
        ime.refreshUnifiedInputBoard();
        ime.getShakeGestureHandler().start();
        ime.getUiCoordinator().onStartInputView();
        if (ime.refreshOnScreenKeyboardShowing() && mainKeyboardView.getKeyboard() != null && mainKeyboardView.getKeyboard().isSlideboardEligible()) {
            ks.getSlideboardManager().showNumericPanel();
        } else {
            ks.getSlideboardManager().show();
        }
    }

    public void onFinishInputViewInternal(boolean finishingInput) {
        ime.superOnFinishInputView(finishingInput);
        ime.cleanupKeyboard();
        ime.resetUiState();
    }

    public void onFinishInputInternal() {
        InputLogic inputLogic = ime.getInputLogic();
        inputLogic.mRichInputConnection.finishComposingText();
        inputLogic.clearComposingText(true);
        ime.superOnFinishInput();
        MainKeyboardView mainKeyboardView = ime.getKeyboardSwitcher().getMainKeyboardView();
        if (mainKeyboardView != null) {
            if (ime.refreshOnScreenKeyboardShowing() && mainKeyboardView.getKeyboard() != null
                    && mainKeyboardView.getKeyboard().isSlideboardEligible()
                    && ime.getKeyboardSwitcher().getSlideboardManager().isShowing()) {
                ime.getKeyboardSwitcher().getSlideboardManager().show();
            }
        }
        ime.setShouldShowOnFirstKeypress(false);
        ime.setWasBackPressed(false);
        ime.getCursorTracker().hide();
        ime.clearLastCursorAnchorInfo();
        CJKSuggestionGridView cjkView = ime.getCjkSuggestionGridView();
        if (cjkView != null) {
            cjkView.setVisible(false);
        }
        ime.resetUiState();
    }

    private boolean shouldReloadKeyboard(EditorInfo editorInfo, boolean restarting) {
        return !(restarting && ime.getSettingsManager().getSettingsValues().shouldInsertSpacesAutomatically(editorInfo));
    }
}
