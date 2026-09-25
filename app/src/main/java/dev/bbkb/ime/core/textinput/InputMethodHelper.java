package dev.bbkb.ime.core.textinput;

import android.app.KeyguardManager;
import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.speech.SpeechRecognizer;

import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;



public final class InputMethodHelper {

    private static final String TAG = "InputMethodHelper";

    @android.annotation.SuppressLint("StaticFieldLeak") // Uses applicationContext (line 53), safe for static
    private static final InputMethodHelper sInstance = new InputMethodHelper();


    // Phase 1g: emojiKeyPressed / micKeyPressed / multifunctionKeyPressed used to live here —
    // three mutable public booleans, armed by KeyEventConverter on a key-down and consumed by
    // KeyEventProcessor on the key-up, in a class that has nothing else to do with key events and
    // could not clear them on any lifecycle event. That down-up pairing is owned by
    // dev.bbkb.ime.core.keyevent.BoardKeyPressTracker now; see PendingKeyAction.

    private Context context;

    private InputMethodHelper() {
    }

    private Context getContext() {
        return this.context;
    }

    public static InputMethodHelper getInstance() {
        return sInstance;
    }

    public static void init(Context context) {
        sInstance.setContext(context);
    }

    private void setContext(Context context) {
        this.context = context.getApplicationContext();
    }

    public static boolean isVoiceInputAvailable() {
        if (!SettingsManager.getInstance().getSettingsValues().editorCapabilities.isMicrophoneAllowed) {
            Logger.debug(TAG, "Voice Input Method is blocked in this control");
            return false;
        }
        
        // First check if speech recognition is available on the device
        // This works with any speech recognition service (Google, on-device, etc.)
        Context context = getInstance().getContext();
        if (context != null && SpeechRecognizer.isRecognitionAvailable(context)) {
            Logger.verbose(TAG, "Speech recognition service is available");
            return true;
        }
        
        // Fallback to checking if Google Voice Typing is enabled as input method
        // (legacy check for backward compatibility)
        switch (SubtypeManager.getInstance().getShortcutImeState()) {
            case DISABLED:
                Logger.debug(TAG, "Voice Input Method is disabled and no speech recognition available");
                return false;
            case ENABLED:
                Logger.debug(TAG, "Voice Input Method is not ready");
                return false;
            case READY:
                Logger.verbose(TAG, "Voice Input Method is available");
                return true;
            default:
                return true;
        }
    }

    public static boolean shouldShowVoiceInputKey() {
        if (!SettingsManager.getInstance().getSettingsValues().showsVoiceInputKey) {
            Logger.debug(TAG, "Voice Input Key is disabled in Settings");
            return false;
        }
        if (!isVoiceInputAvailable()) {
            return false;
        }
        Logger.verbose(TAG, "Voice Input Key is enabled");
        return true;
    }

    public boolean switchToVoiceIme(InputMethodService inputMethodService) {
        if (!isVoiceInputAvailable()) {
            return false;
        }
        SubtypeManager.getInstance().switchToShortcutIme(inputMethodService);
        return true;
    }

    public boolean switchToRapidInputIme(InputMethodService inputMethodService) {
        Logger.info(TAG, "Invoke rapid input IME");
        SubtypeManager.getInstance().switchToRapidInputIme(inputMethodService);
        return true;
    }

    public boolean isInputSettingsAvailable() {
        return InputSettingsLauncher.isInputSettingsAvailable(this.context);
    }

    /**
     * TI-31: the {@code SDK_INT >= 22} half is always true at {@code minSdk 23}, and the
     * {@code inKeyguardRestrictedInputMode()} fallback it guarded has been deprecated since API 16
     * and is redundant once {@code isDeviceLocked()} is unconditionally available. The context is
     * also null until {@code init()} runs, and this is called from the functional-key handler.
     */
    public static boolean isDeviceLocked() {
        Context context = getInstance().getContext();
        if (context == null) {
            return false;
        }
        KeyguardManager keyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguardManager != null && keyguardManager.isDeviceLocked();
    }

    public boolean isDeviceUnlocked() {
        return !isDeviceLocked();
    }

    public boolean isTextOrImMode() {
        Keyboard keyboard = KeyboardSwitcher.getInstance().getCurrentKeyboard();
        int i = keyboard != null ? keyboard.mId.mMode : 0;
        return i == 0 || i == 3;
    }

    public boolean isPortraitNonPasswordField() {
        SettingsValues settingsValues = SettingsManager.getInstance().getSettingsValues();
        return !settingsValues.editorCapabilities.isPassword && settingsValues.displayOrientation == 1;
    }
}
