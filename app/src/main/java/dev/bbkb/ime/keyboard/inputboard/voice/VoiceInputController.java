package dev.bbkb.ime.keyboard.inputboard.voice;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.os.Bundle;
import android.view.View;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.shared.InAppEventBus;
import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.permissions.PermissionRequestHandler;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.textinput.InputMethodHelper;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.locale.ResourceLocaleUtils;
import dev.bbkb.ime.keyboard.inputboard.AbstractBoardController;

import dev.bbkb.ime.BuildConfig;



public class VoiceInputController extends AbstractBoardController<VoiceInputController.Listener>
        implements VoiceInputView.Listener, VoiceRecognitionManager.Callback {

    public static final int KEY_CODE = -27;

    public static final ComponentName LANG_PACK_INSTALL_COMPONENT = new ComponentName("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.greco3.languagepack.InstallActivity");

    private VoiceInputView mVoiceInputView;

    private VoiceRecognitionManager mRecognitionManager;


    private Context mContext;

    private InAppEventBus.EventListener mEventListener = null;

    private static final String ACTION_VOICE_DIALOG_RESULT = "language.mode.not.available.offline.dialog.result";

    private boolean mShowing;
    
    /* Voice mode state - used for toggle logic (like isInEmojiMode for emoji) */
    private boolean isInVoiceMode = false;
    
    /*
     * Toggle voice input on/off. Uses internal state for reliable toggle,
     * similar to how emoji uses isInEmojiMode.
     */
    public void toggleVoiceInput() {
        if (BuildConfig.DEBUG) android.util.Log.d("VoiceInput", "toggleVoiceInput: isInVoiceMode=" + isInVoiceMode);
        if (isInVoiceMode) {
            // Currently in voice mode, close it
            if (BuildConfig.DEBUG) android.util.Log.d("VoiceInput", "toggleVoiceInput: closing voice");
            isInVoiceMode = false;
            cancelVoiceInput();
        } else {
            // Not in voice mode, open it
            if (BuildConfig.DEBUG) android.util.Log.d("VoiceInput", "toggleVoiceInput: opening voice");
            isInVoiceMode = true;
            show();
        }
        if (BuildConfig.DEBUG) android.util.Log.d("VoiceInput", "toggleVoiceInput: after, isInVoiceMode=" + isInVoiceMode);
    }
    
    /* Check if currently in voice input mode. */
    public boolean isInVoiceMode() {
        return isInVoiceMode;
    }

    
    public interface Listener {
        void onVoicePanelShown();

        void onVoicePanelHidden();
    }

    public VoiceInputController(BlackBerryIME blackBerryIME, Listener aVar) {
        super(KEY_CODE, blackBerryIME, aVar);
        this.mContext = blackBerryIME.getApplicationContext();
        this.mRecognitionManager = new VoiceRecognitionManager(this.mContext, this);
    }

    public void onInputViewCreated(View view) {
        // Clear stale reference when input view is recreated (theme/config change).
        // KeyboardSwitcher.createInputView() nulls its voiceInputView and sets a new
        // ViewStub, but this controller's mVoiceInputView would still point at the old detached
        // view. Clearing it here ensures lazy inflation targets the new view hierarchy.
        if (this.mVoiceInputView != null) {
            this.mVoiceInputView.setListener(null);
            this.mVoiceInputView = null;
        }
    }
    
    /**
     * Sets the VoiceInputView reference and listener.
     * Called when the view is lazily inflated from ViewStub.
     */
    public void setVoiceInputView(VoiceInputView voiceInputView) {
        this.mVoiceInputView = voiceInputView;
        if (this.mVoiceInputView != null) {
            this.mVoiceInputView.setListener(this);
        }
    }

    public void showVoiceView() {
        // Ensure voice view is inflated (lazy inflation from ViewStub)
        if (this.mVoiceInputView == null) {
            if (BuildConfig.DEBUG) android.util.Log.d("VoiceInput", "showVoiceView: inflating voice view");
            VoiceInputView view = dev.bbkb.ime.keyboard.KeyboardSwitcher.getInstance().getVoiceInputView();
            if (view != null) {
                setVoiceInputView(view);
            }
        }
        
        boolean hasView = hasView();
        boolean isShowing = hasView && this.mVoiceInputView.isShowing();
        if (BuildConfig.DEBUG) android.util.Log.d("VoiceInput", "showVoiceView: hasView=" + hasView + ", isShowing=" + isShowing);
        if (!hasView || isShowing) {
            if (BuildConfig.DEBUG) android.util.Log.d("VoiceInput", "showVoiceView: returning early");
            return;
        }
        if (BuildConfig.DEBUG) android.util.Log.d("VoiceInput", "showVoiceView: showing voice view");
        this.mShowing = true;
        this.listener.onVoicePanelShown();
        this.mVoiceInputView.show();
        this.mShowing = false;
        
        // Only auto-start listening if the setting is enabled
        // Through PrefsManager, like every other site in this package - it exists
        // precisely to avoid repeated getDefaultSharedPreferences() calls (audit IB-21).
        boolean autoStart = SettingsManager.isVoiceInputAutoStart(
            dev.bbkb.ime.core.settings.PrefsManager.INSTANCE.getPrefs(this.mContext));
        if (autoStart) {
            onStartListening();
        }
    }

    public void hideVoiceView() {
        if (hasView() && this.mVoiceInputView.isShowing()) {
            Logger.debug("VoiceInput", "Hiding voice input view");
            this.mVoiceInputView.hide();
            this.mRecognitionManager.stopListening();
            this.listener.onVoicePanelHidden();
        }
    }

    public boolean hasView() {
        return this.mVoiceInputView != null;
    }

    /** Recognizer audio level, forwarded to the Material waveform. */
    public void onAudioLevel(float rmsDb) {
        if (hasView()) {
            this.mVoiceInputView.setAudioLevel(rmsDb);
        }
    }

    public boolean isViewShowing() {
        return hasView() && (this.mShowing || this.mVoiceInputView.isShowing());
    }

    @Override
    protected void onDestroy() {
        if (hasView()) {
            this.mVoiceInputView.setOnClickListener(null);
            this.mVoiceInputView = null;
        }
        // The dialog listener used to be unsubscribed only from inside its own
        // handler, so a dialog dismissed without posting left it registered on the
        // process-wide event bus forever (audit IB-8).
        unsubscribeDialogListener();
        this.mRecognitionManager.destroy();
    }

    @Override
    protected View peekBoardView() {
        return this.mVoiceInputView;
    }

    @Override // dev.bbkb.ime.keyboard.inputboard.voice.VoiceInputView.Listener
    public void onDelete() {
        BlackBerryIME blackBerryIME = this.ime;
        if (blackBerryIME != null) {
            blackBerryIME.onSwipeDelete();
        }
    }

    @Override // dev.bbkb.ime.keyboard.inputboard.voice.VoiceInputView.Listener
    public void onOpenSettings() {
        Intent intent = new Intent(this.mContext, (Class<?>) dev.bbkb.ime.core.settings.ComposeSettingsActivity.class);
        intent.putExtra("screen", "voice_input_main");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        this.mContext.startActivity(intent);
    }

    @Override // dev.bbkb.ime.keyboard.inputboard.voice.VoiceInputView.Listener
    public void onStartListening() {
        if (stopIfListening(this.mRecognitionManager.getMode())) {
            return;
        }
        this.mRecognitionManager.startDictation();
    }

    private boolean stopIfListening(VoiceRecognitionManager.Mode aVar) {
        if (aVar == VoiceRecognitionManager.Mode.NONE) {
            return false;
        }
        this.mRecognitionManager.stopListening();
        return true;
    }

    @Override
    public void onDictationResult(String str) {
        BlackBerryIME blackBerryIME = this.ime;
        if (blackBerryIME == null) {
            return;
        }
        String strM4483c = blackBerryIME.getInputLogic().getWordSeparator(str);
        this.ime.uiUpdateHandler.postCommitText(str + strM4483c);
    }

    @Override
    public void onStateChanged(VoiceRecognitionManager.Mode aVar, int i) {
        if (hasView()) {
            this.mVoiceInputView.updateState(aVar, i);
        }
    }

    @Override
    public void onPermissionNeeded() {
        new PermissionRequestHandler(this.mContext, new PermissionRequestHandler.Callback() {
            @Override
            public void onPermissionResult(String str, PermissionRequestHandler.Result cVar) {
                boolean z = cVar == PermissionRequestHandler.Result.PERMISSION_GRANTED;
                if (VoiceInputController.this.ime != null) {
                    VoiceInputController.this.ime.showImeWindow();
                }
                if (z && VoiceInputController.this.mRecognitionManager.getMode() == VoiceRecognitionManager.Mode.NONE) {
                    VoiceInputController.this.showVoiceView();
                }
            }
        }).requestPermission("android.permission.RECORD_AUDIO", this.mContext.getString(R.string.voice_input_permission_rationale));
    }

    /** Drops the language-pack dialog listener from the process-wide event bus. */
    private void unsubscribeDialogListener() {
        if (this.mEventListener != null) {
            InAppEventBus.getInstance().unsubscribe(ACTION_VOICE_DIALOG_RESULT, this.mEventListener);
            this.mEventListener = null;
        }
    }

    @Override
    public void onLanguageUnavailable() {
        if (this.mEventListener != null) {
            return;
        }
        this.mEventListener = new InAppEventBus.EventListener() {
            @Override
            public void onEvent(String action, Bundle extras) {
                if (!ACTION_VOICE_DIALOG_RESULT.equals(action)) {
                    return;
                }
                try {
                    // VoiceInputDialog posts this event for EVERY outcome, CANCEL and
                    // onUserLeaveHint included, so branching on the action alone
                    // launched the Google language-pack installer even when the user
                    // tapped Cancel (audit IB-8).
                    int result = extras == null
                            ? -1
                            : extras.getInt(VoiceInputDialog.EXTRA_RESULT_CODE, -1);
                    if (result != VoiceInputDialog.Result.OK.ordinal()) {
                        return;
                    }
                    Intent intent2 = new Intent(Intent.ACTION_MAIN);
                    intent2.setComponent(VoiceInputController.LANG_PACK_INSTALL_COMPONENT);
                    intent2.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    VoiceInputController.this.mContext.startActivity(intent2);
                } finally {
                    // Always unsubscribe: this listener is held by the process-wide
                    // InAppEventBus singleton and retains the controller (and through
                    // it the IME) for as long as it stays registered.
                    VoiceInputController.this.unsubscribeDialogListener();
                }
            }
        };
        InAppEventBus.getInstance().subscribe(ACTION_VOICE_DIALOG_RESULT, this.mEventListener);
        String str = String.format(this.mContext.getString(R.string.voice_input_lang_pack_available_offline_mode), ResourceLocaleUtils.getSubtypeLocaleDisplayName(SubtypeManager.getInstance().getCurrentSubtypeLocale().toString()));
        Intent intent = new Intent();
        intent.setClass(this.mContext, VoiceInputDialog.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra("explanation_text", str);
        intent.putExtra("result_receiver", ACTION_VOICE_DIALOG_RESULT);
        this.mContext.startActivity(intent);
    }

    /**
     * Overrides the {@link AbstractBoardController} guard rather than implementing {@link #onHide()}:
     * this must run even when the view is not showing. A recognizer session can outlive its view
     * (§5.6 item 5), and UIM-11 was exactly the case where a lifecycle-path hide skipped the cancel
     * and left the microphone held.
     */
    @Override
    public void hide() {
        // Don't reset voice mode if we're in the middle of showing (mShowing flag)
        // This prevents the show process from accidentally resetting the state
        if (this.mShowing) {
            if (BuildConfig.DEBUG) android.util.Log.d("VoiceInput", "hide() called during show - not resetting voice mode");
            return;
        }
        // UIM-11 fix: Always cancel recognition before hiding.
        // This guarantees the speech recognizer stops and releases the mic,
        // even when hide() is called from lifecycle paths (onWindowHidden and related lifecycle paths).
        cancelVoiceInput();
    }

    /** Unused: {@link #hide()} is overridden outright, for the reason documented on it. */
    @Override
    protected void onHide() {
        cancelVoiceInput();
    }

    /**
     * Also overrides the guard: when voice input is disabled this does not open a board at all, it
     * hands off to the system voice IME, and that must happen whatever this board's view state is.
     */
    @Override
    public void show() {
        if (BuildConfig.DEBUG) android.util.Log.d("VoiceInput", "show() called, ime=" + (this.ime != null ? "not null" : "null"));
        if (this.ime == null) {
            if (BuildConfig.DEBUG) android.util.Log.d("VoiceInput", "show() returning early - ime is null");
            return;
        }
        boolean voiceEnabled = SettingsManager.getInstance().getSettingsValues().isVoiceInputEnabled;
        if (BuildConfig.DEBUG) android.util.Log.d("VoiceInput", "isVoiceInputEnabled=" + voiceEnabled);
        if (voiceEnabled) {
            if (BuildConfig.DEBUG) android.util.Log.d("VoiceInput", "Calling showVoiceView() to show voice input");
            showVoiceView();
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("VoiceInput", "Voice disabled, opening voice settings");
            InputMethodHelper.getInstance().switchToVoiceIme((InputMethodService) this.ime);
        }
    }

    /** Unused: {@link #show()} is overridden outright, for the reason documented on it. */
    @Override
    protected void onShow() {
        showVoiceView();
    }

    @Override
    public boolean isShowing() {
        return isViewShowing();
    }

    @Override
    public boolean isEnabled() {
        return InputMethodHelper.isVoiceInputAvailable();
    }
    
    /**
     * Cancel any active voice recognition and hide the voice input view.
     * Called when user explicitly closes the voice input (e.g., pressing mic key again).
     */
    public void cancelVoiceInput() {
        Logger.debug("VoiceInput", "Canceling voice input");
        
        // Reset voice mode state
        isInVoiceMode = false;
        
        // Cancel the speech recognizer
        if (this.mRecognitionManager != null) {
            this.mRecognitionManager.stopListening();  // Stops and cancels recognition
        }
        // Hide the view if showing
        if (hasView() && this.mVoiceInputView.isShowing()) {
            this.mVoiceInputView.hide();
            if (this.listener != null) {
                this.listener.onVoicePanelHidden();
            }
        }
    }
}
