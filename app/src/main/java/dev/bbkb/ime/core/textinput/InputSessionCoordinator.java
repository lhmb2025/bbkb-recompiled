package dev.bbkb.ime.core.textinput;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.content.res.Resources;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputBinding;

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

/**
 * Coordinates input session lifecycle events extracted from BlackBerryIME.
 * Handles onStartInput, onStartInputView, onFinishInputView, onFinishInput internal logic.
 *
 * This class reduces BlackBerryIME's responsibility count by encapsulating
 * the input session state machine into a focused coordinator.
 */
public class InputSessionCoordinator {

    private static final String TAG = "InputSessionCoord";

    private final BlackBerryIME ime;

    public InputSessionCoordinator(BlackBerryIME ime) {
        this.ime = ime;
    }

    /**
     * Internal handler for onStartInput lifecycle event.
     * Extracted from BlackBerryIME.startInputInternal().
     */
    public void onStartInputInternal(EditorInfo editorInfo, boolean restarting) {
        Logger.debug(TAG, "onStartInputInternal(): restarting=" + restarting);
        ime.superOnStartInput(editorInfo, restarting);
        if (shouldReloadKeyboard(editorInfo, restarting)) {
            ime.setNeedsKeyboardReload(true);
        }
    }

    /**
     * Internal handler for onStartInputView lifecycle event.
     * Extracted from BlackBerryIME.startInputViewInternal().
     */
    public void onStartInputViewInternal(EditorInfo editorInfo, boolean restarting) throws Resources.NotFoundException {
        Logger.debug(TAG, "onStartInputViewInternal(): restarting=" + restarting);
        boolean z2 = false;
        ime.setNeedsKeyboardReload(false);
        ime.superOnStartInputView(editorInfo, restarting);
        ime.getRichInputMethodManager().clearSubtypeCaches();
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
        if (Build.VERSION.SDK_INT >= 24) {
            if (ime.getSubtypeManager().shouldRemoveActiveKeyboardLocale(ime, Resources.getSystem().getConfiguration().getLocales())) {
                ime.updateSuggestionsFromSubtype(InputSource.INTERNAL);
            }
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
            // Audit LC-7: this PKB branch skips resetInputState, so mCommitType survived the field
            // switch. Value 4 is the "phantom auto-space" state — the next word typed in the NEW
            // field would get an auto-space it never earned. resetInputState zeroes it
            // (InputLogic.java:206); do the same here for the branch that bypasses it.
            inputLogic.mCommitType = 0;
            if (!inputLogic.mRichInputConnection.resetConnection(editorInfo.initialSelStart, editorInfo.initialSelEnd, true)) {
                ime.getUiUpdateHandler().postUpdateSwitchKeyboard(shouldInit, 5);
                z2 = true;
            } else {
                inputLogic.mRichInputConnection.recalibrateCursorPosition();
                ime.getUiUpdateHandler().postUpdateShiftState(true, true);
            }
            // Audit LC-1: updateDynamicLearningState() lived only in the VKB branch, so on a
            // KEY2 dynamicLearningEnabled and TextContextTracker.enabled went stale across every
            // field switch — a password or no-suggestions field could leave learning disabled for
            // the next ordinary field, or vice versa. Its internal mLastInputType short-circuit
            // (InputLogic:1761) makes the call cheap when nothing changed.
            inputLogic.updateDynamicLearningState();
            // Audit LC-8: requestCursorUpdates was issued from exactly one place —
            // InputLogic.resetInputState — which this PKB branch skips. So on a KEY2 no editor
            // reached through the start-input path ever got cursor-anchor monitoring, and
            // mMonitorCursorUpdates sat stale (typically FALSE), silently disabling
            // shouldShowAddToDictionary and starving the FCC cursor tracker and commit indicator.
            // Issue it per editor, against the connection we just reset.
            inputLogic.mRichInputConnection.requestCursorUpdates(true, true);
        }
        SettingsValues updatedSettings = ime.getSettingsManager().getSettingsValues();
        if (shouldInit) {
            mainKeyboardView.closing();
            ime.applyAutoCorrectionSettings(updatedSettings);
            ks.startInput(editorInfo, ime.getCurrentInputType(), ime.getCurrentImeOptions());

            AuxBarManager auxBarManager = ime.getAuxBarManager();
            if (auxBarManager != null && ks.getKeyboardBuilder() != null) {
                auxBarManager.setCurrentLocale(ime.getSubtypeManager().getCurrentSubtypeLocale());
            }

            ime.loadSettings();
            updatedSettings = ime.getSettingsManager().getSettingsValues();
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

    /**
     * Internal handler for onFinishInputView lifecycle event.
     * Extracted from BlackBerryIME.finishInputViewInternal().
     */
    public void onFinishInputViewInternal(boolean finishingInput) {
        ime.superOnFinishInputView(finishingInput);
        ime.cleanupKeyboard();
        ime.resetUiState();
    }

    /**
     * Internal handler for onFinishInput lifecycle event.
     * Extracted from BlackBerryIME.finishInputInternal().
     */
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
        ime.getInputLogic().resetTextContextTracker();
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
