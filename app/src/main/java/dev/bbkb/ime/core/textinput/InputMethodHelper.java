package dev.bbkb.ime.core.textinput;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.inputmethodservice.InputMethodService;
import android.speech.SpeechRecognizer;
import android.view.inputmethod.EditorInfo;

import androidx.core.content.ContextCompat;

import dev.bbkb.ime.core.locale.RichInputMethodManager;
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
        registerLockStateReceiver(this.context);
    }

    /**
     * The last answer of {@link KeyguardManager#isDeviceLocked()}, or null when it has to be asked
     * again. Asking was a TrustManager binder call on every field focus. The answer can only turn
     * from unlocked to locked while the screen is off (or on the lock screen itself, which is
     * SystemUI's window; see {@link #onEditorStarted}), and an unlock announces itself with
     * USER_PRESENT, so: the screen going off or on forgets it, USER_PRESENT sets it to unlocked,
     * and the first field after either asks once. A cold start asks on the first field.
     */
    private static volatile Boolean sDeviceLocked;

    private static volatile boolean sLockReceiverRegistered;

    /** SystemUI's package: its fields (the lock screen's password entry) re-ask the lock state. */
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    private static synchronized void registerLockStateReceiver(Context appContext) {
        if (sLockReceiverRegistered || appContext == null) {
            return;
        }
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        try {
            ContextCompat.registerReceiver(appContext, new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    onLockStateBroadcast(intent.getAction());
                }
            }, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            sLockReceiverRegistered = true;
        } catch (RuntimeException e) {
            // Without the events the state cannot be followed; isDeviceLocked keeps asking.
            Logger.warn(TAG, "Lock-state receiver not registered: " + e);
        }
    }

    /** Package-private for the unit test. */
    static void onLockStateBroadcast(String action) {
        if (Intent.ACTION_USER_PRESENT.equals(action)) {
            sDeviceLocked = Boolean.FALSE;
        } else {
            sDeviceLocked = null;
        }
    }

    /**
     * A field is starting. The lock screen's own field belongs to SystemUI, and the device can
     * be locked with the screen on (Lockdown from the power menu), so a SystemUI field re-asks.
     */
    public static void onEditorStarted(EditorInfo editorInfo) {
        if (editorInfo != null && SYSTEM_UI_PACKAGE.equals(editorInfo.packageName)) {
            sDeviceLocked = null;
        }
    }

    /** For tests: forget the receiver and the kept answer. */
    static void resetLockStateForTest() {
        sDeviceLocked = null;
        sLockReceiverRegistered = false;
    }

    public static boolean isVoiceInputAvailable() {
        if (!SettingsManager.getInstance().getSettingsValues().editorCapabilities.isMicrophoneAllowed) {
            Logger.debug(TAG, "Voice Input Method is blocked in this control");
            return false;
        }
        
        // First check if speech recognition is available on the device
        // This works with any speech recognition service (Google, on-device, etc.)
        Context context = getInstance().getContext();
        if (context != null && isRecognitionAvailable(context)) {
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
        final Boolean known = sDeviceLocked;
        if (known != null) {
            return known;
        }
        KeyguardManager keyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        final boolean locked = keyguardManager != null && keyguardManager.isDeviceLocked();
        if (sLockReceiverRegistered) {
            sDeviceLocked = locked;
        }
        return locked;
    }

    /** The cached recogniser query when the input-method caches exist, else the direct one. */
    private static boolean isRecognitionAvailable(Context context) {
        if (RichInputMethodManager.isInitialized()) {
            return RichInputMethodManager.getInstance().isRecognitionAvailable(context);
        }
        return SpeechRecognizer.isRecognitionAvailable(context);
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
