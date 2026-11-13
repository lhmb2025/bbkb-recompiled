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

    /* Latched voice mode, drives the toggle. */
    private boolean isInVoiceMode = false;

    public void toggleVoiceInput() {
        if (BuildConfig.DEBUG) android.util.Log.d("VoiceInput", "toggleVoiceInput: isInVoiceMode=" + isInVoiceMode);
        if (isInVoiceMode) {
            if (BuildConfig.DEBUG) android.util.Log.d("VoiceInput", "toggleVoiceInput: closing voice");
            isInVoiceMode = false;
            cancelVoiceInput();
        } else {
            // show() puts the mode back when there is no recognition service, so a refused open
            // latches nothing.
            if (BuildConfig.DEBUG) android.util.Log.d("VoiceInput", "toggleVoiceInput: opening voice");
            isInVoiceMode = true;
            show();
        }
        if (BuildConfig.DEBUG) android.util.Log.d("VoiceInput", "toggleVoiceInput: after, isInVoiceMode=" + isInVoiceMode);
    }

    public boolean isInVoiceMode() {
        return isInVoiceMode;
    }

    
    public interface Listener {
        void onVoicePanelShown();

        void onVoicePanelHidden();
    }

    /** @param aVar receives panel shown/hidden events */
    public VoiceInputController(BlackBerryIME blackBerryIME, Listener aVar) {
        super(KEY_CODE, blackBerryIME, aVar);
        this.mContext = blackBerryIME.getApplicationContext();
        this.mRecognitionManager = new VoiceRecognitionManager(this.mContext, this);
    }

    public void onInputViewCreated(View view) {
        // The input view was recreated: drop the old detached view so lazy inflation targets the
        // new ViewStub.
        if (this.mVoiceInputView != null) {
            this.mVoiceInputView.setListener(null);
            this.mVoiceInputView = null;
        }
    }

    public void setVoiceInputView(VoiceInputView voiceInputView) {
        this.mVoiceInputView = voiceInputView;
        if (this.mVoiceInputView != null) {
            this.mVoiceInputView.setListener(this);
        }
    }

    public void showVoiceView() {
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
        // A dialog dismissed without posting would otherwise leave the listener on the bus.
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
        if (!VoiceRecognitionAvailability.hasRecognitionService(this.mContext)) {
            // The service went away while the board was up: say so instead of starting a doomed session.
            noticeNoRecognitionService();
            return;
        }
        this.mRecognitionManager.startDictation();
    }

    /**
     * The gate every way of opening voice input asks first. With built-in voice input on and no
     * recognition service to bind, it shows the "No selected voice recognition service" notice and
     * answers false, and the caller opens nothing. With built-in voice input off, opening hands off
     * to the system voice keyboard, which needs no recogniser of ours, so it answers true.
     */
    public boolean ensureRecognitionService() {
        if (!SettingsManager.getInstance().getSettingsValues().isVoiceInputEnabled) {
            return true;
        }
        if (VoiceRecognitionAvailability.hasRecognitionService(this.mContext)) {
            return true;
        }
        noticeNoRecognitionService();
        return false;
    }

    /**
     * The on-screen mic while listening: finish and keep what was heard. The mic key and close
     * path use {@link #cancelVoiceInput}, which discards.
     *
     * @param aVar the recognizer's current mode
     */
    private boolean stopIfListening(VoiceRecognitionManager.Mode aVar) {
        if (aVar == VoiceRecognitionManager.Mode.NONE) {
            return false;
        }
        this.mRecognitionManager.stopDictation();
        return true;
    }

    /** @param str the final transcription */
    @Override
    public void onDictationResult(String str) {
        BlackBerryIME blackBerryIME = this.ime;
        if (blackBerryIME == null) {
            return;
        }
        String strM4483c = blackBerryIME.getInputLogic().getWordSeparator(str);
        this.ime.uiUpdateHandler.postCommitText(str + strM4483c);
    }

    /**
     * @param aVar the recognizer's new mode
     * @param i the status passed through to {@code VoiceInputView.updateState}
     */
    @Override
    public void onStateChanged(VoiceRecognitionManager.Mode aVar, int i) {
        if (hasView()) {
            this.mVoiceInputView.updateState(aVar, i, this.mRecognitionManager.isContinuous());
        }
    }

    @Override
    public void onPermissionNeeded() {
        new PermissionRequestHandler(this.mContext, new PermissionRequestHandler.Callback() {
            /**
             * @param str the permission asked for
             * @param cVar whether it was granted
             */
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

    /**
     * The app chosen as the speech recognizer has no microphone permission. Requesting ours (which
     * this keyboard already holds) cannot help, so the board says which app to fix.
     */
    @Override
    public void onRecognizerNeedsPermission(String appLabel) {
        if (isViewShowing()) {
            showNotice(this.mContext.getString(R.string.voice_status_recognizer_needs_permission, appLabel));
        }
    }

    /**
     * The app chosen as the speech recognizer has the microphone but would not serve this keyboard.
     * Only another recognizer can help, so the board says so.
     */
    @Override
    public void onRecognizerRefused(String appLabel) {
        if (isViewShowing()) {
            showNotice(this.mContext.getString(R.string.voice_status_recognizer_refused, appLabel));
        }
    }

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
                    // VoiceInputDialog posts this for every outcome (Cancel and onUserLeaveHint
                    // included); only OK launches the installer.
                    int result = extras == null
                            ? -1
                            : extras.getInt(VoiceInputDialog.EXTRA_RESULT_CODE, -1);
                    if (result != VoiceInputDialog.Result.OK.ordinal()) {
                        return;
                    }
                    Intent intent2 = new Intent(Intent.ACTION_MAIN);
                    intent2.setComponent(VoiceInputController.LANG_PACK_INSTALL_COMPONENT);
                    intent2.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    // The Google installer may be absent or invisible to this app: fall back to the
                    // in-board message naming the language.
                    if (intent2.resolveActivity(VoiceInputController.this.mContext.getPackageManager()) == null) {
                        VoiceInputController.this.onLanguageNotSupported(VoiceInputController.this.mRecognitionManager.getLastLanguageTag());
                        return;
                    }
                    try {
                        VoiceInputController.this.mContext.startActivity(intent2);
                    } catch (android.content.ActivityNotFoundException e) {
                        VoiceInputController.this.onLanguageNotSupported(VoiceInputController.this.mRecognitionManager.getLastLanguageTag());
                    }
                } finally {
                    // The bus singleton would otherwise retain the controller and the IME.
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

    @Override
    public void onLanguageNotSupported(String languageTag) {
        if (hasView()) {
            this.mVoiceInputView.showLanguageError(VoiceLanguageTags.displayName(languageTag));
        }
    }

    /**
     * The recogniser is fetching the language: say so where "Listening" normally goes. The first
     * report ({@code percent} -1) falls back to a toast under the styles with no status line;
     * progress after that is only worth showing where it can be updated in place.
     */
    @Override
    public void onLanguageDownloading(String languageTag, int percent) {
        if (!isViewShowing()) {
            return;
        }
        final String downloading = this.mContext.getString(
                R.string.voice_status_language_downloading, VoiceLanguageTags.displayName(languageTag));
        if (percent < 0) {
            showNotice(downloading);
        } else {
            this.mVoiceInputView.showStatusMessage(downloading + " "
                    + java.text.NumberFormat.getPercentInstance().format(Math.min(percent, 100) / 100.0));
        }
    }

    @Override
    public void onLanguageDownloadPending(String languageTag) {
        if (isViewShowing()) {
            showNotice(this.mContext.getString(R.string.voice_status_language_download_pending,
                    VoiceLanguageTags.displayName(languageTag)));
        }
    }

    /**
     * No recognition service. A board that is up stays up, idle, and says so; otherwise voice mode
     * goes back off (so the next press is a fresh open) and a toast says so.
     *
     * @return whether the board is up (and so still open)
     */
    private boolean noticeNoRecognitionService() {
        if (BuildConfig.DEBUG) android.util.Log.d("VoiceInput", "no recognition service");
        if (isViewShowing()) {
            showNotice(R.string.voice_status_no_recognition_service);
            return true;
        }
        this.isInVoiceMode = false;
        showToast(R.string.voice_status_no_recognition_service);
        return false;
    }

    /**
     * A started session found no service. Unlike the gate, this arrives after the open was
     * reported, so a board that is no longer up is reported closed too, or the coordinator would
     * spend the next press "closing" it.
     */
    @Override
    public void onNoRecognitionService() {
        if (noticeNoRecognitionService() || this.ime == null) {
            return;
        }
        dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardManager unifiedManager =
                this.ime.getKeyboardSwitcher().getUnifiedInputBoardManager();
        if (unifiedManager != null) {
            unifiedManager.reportBoardClosed(KEY_CODE);
        }
    }

    @Override
    public void onRecognitionError(int messageRes) {
        if (isViewShowing()) {
            showNotice(messageRes);
        }
    }

    /** Partial transcription, shown in the editor as composing text until the final result replaces it. */
    @Override
    public void onPartialResult(String text) {
        if (this.ime != null) {
            this.ime.uiUpdateHandler.postSetComposingText(text);
        }
    }

    @Override
    public void onDictationAbandoned() {
        if (this.ime != null) {
            this.ime.uiUpdateHandler.finishVoiceComposingText();
        }
    }

    /** The board's status line when it has one (Material); a toast under the styles that do not. */
    private void showNotice(int messageRes) {
        if (hasView() && this.mVoiceInputView.showStatusMessage(messageRes)) {
            return;
        }
        showToast(messageRes);
    }

    /** {@link #showNotice(int)} for a message already formatted. */
    private void showNotice(CharSequence message) {
        if (hasView() && this.mVoiceInputView.showStatusMessage(message)) {
            return;
        }
        android.widget.Toast.makeText(this.mContext, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void showToast(int messageRes) {
        android.widget.Toast.makeText(this.mContext, messageRes, android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * Overrides the {@link AbstractBoardController} guard: must cancel even when the view is not
     * showing, since a recognizer session can outlive its view and would keep the microphone.
     */
    @Override
    public void hide() {
        // A hide raised by the show itself must not reset voice mode.
        if (this.mShowing) {
            if (BuildConfig.DEBUG) android.util.Log.d("VoiceInput", "hide() called during show - not resetting voice mode");
            return;
        }
        cancelVoiceInput();
    }

    /** Unused: {@link #hide()} is overridden outright. */
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
            // Every opener (mic key, on-screen -27/-7 key, UIM bar) comes through here.
            if (!isViewShowing() && !ensureRecognitionService()) {
                return;
            }
            if (BuildConfig.DEBUG) android.util.Log.d("VoiceInput", "Calling showVoiceView() to show voice input");
            showVoiceView();
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("VoiceInput", "Voice disabled, opening voice settings");
            InputMethodHelper.getInstance().switchToVoiceIme((InputMethodService) this.ime);
        }
    }

    /** Unused: {@link #show()} is overridden outright. */
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

    /** Cancel recognition (discarding it) and hide the view: an explicit close. */
    public void cancelVoiceInput() {
        Logger.debug("VoiceInput", "Canceling voice input");

        isInVoiceMode = false;

        if (this.mRecognitionManager != null) {
            this.mRecognitionManager.stopListening();
        }
        if (hasView() && this.mVoiceInputView.isShowing()) {
            this.mVoiceInputView.hide();
            if (this.listener != null) {
                this.listener.onVoicePanelHidden();
            }
        }
    }
}
