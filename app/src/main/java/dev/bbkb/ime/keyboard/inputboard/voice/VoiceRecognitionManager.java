package dev.bbkb.ime.keyboard.inputboard.voice;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.shared.Logger;

import java.util.ArrayList;



public class VoiceRecognitionManager {

    private static final String TAG = "VoiceRecognition";

    /**
     * Recognition lifecycle states passed to {@link Callback#onStateChanged}. These
     * were bare 1/2/3 literals, decoded on the far side with an opaque
     * {@code (i & 1) != 0} bit test (audit IB-16).
     */
    public static final int STATE_STARTED = 1;

    public static final int STATE_READY_FOR_SPEECH = 2;

    public static final int STATE_STOPPED = 3;

    private SpeechRecognizer mSpeechRecognizer;

    private Callback mCallback;

    private VoiceInputController mController;

    private Mode mMode;

    private final Handler mHandler;

    private final Runnable mTimeoutRunnable;

    private boolean mReadyForSpeech;

    /** Set when we cancel deliberately, so the resulting ERROR_CLIENT is ignored. */
    private boolean mCancelRequested;

    /** Application context, kept only to name ourselves as the recogniser's calling package. */
    private final Context mContext;

    /** The tag the last dictation request carried; what the error and the chip should name. */
    private String mLastLanguageTag = SettingsManager.DEFAULT_VOICE_INPUT_LANGUAGE;

    
    public enum Mode {
        DICTATION,
        NONE
    }

    
    public interface Callback {
        void onStateChanged(Mode aVar, int i);

        void onDictationResult(String str);

        void onPermissionNeeded();

        void onLanguageUnavailable();

        /** The recogniser refused the language itself (error 12 or 13); {@code languageTag} is what was sent. */
        void onLanguageNotSupported(String languageTag);
    }

    public VoiceRecognitionManager(Context context, VoiceInputController c1122b) {
        this.mContext = context.getApplicationContext();
        this.mSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
        this.mSpeechRecognizer.setRecognitionListener(new RecognitionListenerImpl());
        this.mCallback = c1122b;
        this.mController = c1122b;
        this.mMode = Mode.NONE;
        this.mHandler = new Handler(android.os.Looper.getMainLooper());
        this.mTimeoutRunnable = new Runnable() {
            @Override // java.lang.Runnable
            public void run() {
                if (!VoiceRecognitionManager.this.mReadyForSpeech || VoiceRecognitionManager.this.mMode == Mode.NONE) {
                    return;
                }
                VoiceRecognitionManager.this.stopListening();
            }
        };
    }

    public void startDictation() {
        Logger.debug(TAG, "Dictation button pressed");
        SettingsValues c0804dM5050c = SettingsManager.getInstance().getSettingsValues();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        // One normaliser for both sources. Google's service treats the raw subtype locale
        // ("en_US", "de_CH", "zh_CN_pinyin") as invalid and silently dictates in the phone's
        // default language instead (KEY2, 2026-09-28); it wants a hyphenated BCP-47 tag.
        final SubtypeManager subtypes = SubtypeManager.getInstance();
        final String keyboardLocale = subtypes.getCurrentSubtype() != null ? subtypes.getCurrentSubtype().getLocale() : null;
        final String tag = VoiceLanguageTags.effectiveTag(c0804dM5050c.voiceInputUseInputLanguage, keyboardLocale, c0804dM5050c.voiceInputLanguageList);
        this.mLastLanguageTag = tag;
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag);
        if (c0804dM5050c.voiceInputUseInputLanguage && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // A multi-language keyboard's extra prediction languages: let the recogniser detect
            // and switch between them rather than hear everything as the primary language.
            final ArrayList<String> allowed = VoiceLanguageTags.allowedTags(tag, subtypes.getCurrentSubtypeAdditionalLocales());
            if (allowed.size() > 1) {
                intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
                intent.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, allowed);
                intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, true);
                intent.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, allowed);
            }
        }
        // The SDK_INT >= 23 gate here was dead: minSdk is 23.
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, c0804dM5050c.voiceInputPreferOffline);
        // "Block offensive words" was a dead flag for dictation: without this extra the
        // recogniser applies its own default (mask), so turning the setting off changed
        // nothing. SettingsValues is re-read per dictation, so a toggle lands on the next tap.
        intent.putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, c0804dM5050c.blockPotentiallyOffensiveWords);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        // Our own package: recognisers attribute the request to whatever this names, so a
        // hardcoded id would credit (and, on some ROMs, be permission-checked as) a different app.
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, this.mContext.getPackageName());
        startRecognition(Mode.DICTATION, intent);
    }

    public void stopListening() {
        if (this.mSpeechRecognizer == null) {
            return;
        }
        Logger.debug(TAG, "Stopped listening to mode " + this.mMode);
        // cancel() fires an async onError(ERROR_CLIENT); mark it deliberate so the
        // transient-error hack below doesn't re-arm mMode (which made the mic
        // untappable: every tap "stopped" a phantom session forever).
        this.mCancelRequested = true;
        this.mSpeechRecognizer.stopListening();
        this.mSpeechRecognizer.cancel();
        this.mMode = Mode.NONE;
        notifyState(STATE_STOPPED);
    }

    /**
     * Audit IB-7: destroy() neither nulled the recognizer nor cancelled the 6 s
     * timeout runnable, so a surviving callback could reach a destroyed recognizer up
     * to 6 s after teardown - and UnifiedInputBoardManager.closeBoard calls
     * cancelVoiceInput() unconditionally on keycode -27, i.e. after
     * VoiceInputController.destroy() has already run.
     */
    public void destroy() {
        this.mHandler.removeCallbacks(this.mTimeoutRunnable);
        SpeechRecognizer speechRecognizer = this.mSpeechRecognizer;
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
            speechRecognizer.destroy();
            this.mSpeechRecognizer = null;
        }
        this.mMode = Mode.NONE;
        this.mReadyForSpeech = false;
    }

    private void startRecognition(Mode aVar, Intent intent) {
        if (this.mSpeechRecognizer == null) {
            return;
        }
        this.mMode = aVar;
        this.mReadyForSpeech = false;
        this.mCancelRequested = false;
        notifyState(STATE_STARTED);
        this.mSpeechRecognizer.startListening(intent);
        this.mHandler.removeCallbacks(this.mTimeoutRunnable);
        this.mHandler.postDelayed(this.mTimeoutRunnable, 6000L);
    }

    public Mode getMode() {
        return this.mMode;
    }

    /** The tag the last dictation request was sent with. */
    public String getLastLanguageTag() {
        return this.mLastLanguageTag;
    }

    public void notifyState(int i) {
        this.mCallback.onStateChanged(this.mMode, i);
    }

    
    protected class RecognitionListenerImpl implements RecognitionListener {
        @Override // android.speech.RecognitionListener
        public void onBufferReceived(byte[] bArr) {
        }

        protected RecognitionListenerImpl() {
        }

        @Override // android.speech.RecognitionListener
        public void onBeginningOfSpeech() {
            Logger.info(VoiceRecognitionManager.TAG, "onBeginningOfSpeech");
            VoiceRecognitionManager.this.mHandler.removeCallbacks(VoiceRecognitionManager.this.mTimeoutRunnable);
        }

        @Override // android.speech.RecognitionListener
        public void onEndOfSpeech() {
            Logger.debug(VoiceRecognitionManager.TAG, "onEndOfSpeech, ended mode = " + VoiceRecognitionManager.this.mMode);
            VoiceRecognitionManager.this.mReadyForSpeech = false;
            VoiceRecognitionManager.this.notifyState(STATE_STOPPED);
        }

        @Override // android.speech.RecognitionListener
        public void onError(int i) {
            Logger.debug(VoiceRecognitionManager.TAG, "error = " + i);
            VoiceRecognitionManager.this.mMode = Mode.NONE;
            VoiceRecognitionManager.this.mReadyForSpeech = false;
            // Deliberate stop/cancel: the session is over; skip the transient-error
            // handling entirely (notably the case-5 mMode re-arm).
            if (VoiceRecognitionManager.this.mCancelRequested) {
                VoiceRecognitionManager.this.mCancelRequested = false;
                if (i != 5) {
                    VoiceRecognitionManager.this.notifyState(STATE_STOPPED);
                }
                return;
            }
            if (i != 5) {
                VoiceRecognitionManager.this.notifyState(STATE_STOPPED);
            }
            switch (i) {
                case 4:
                    VoiceRecognitionManager.this.mCallback.onLanguageUnavailable();
                    break;
                case 5:
                    if (VoiceRecognitionManager.this.mController.isViewShowing()) {
                        VoiceRecognitionManager.this.mMode = Mode.DICTATION;
                        break;
                    }
                    break;
                case 6:
                    VoiceRecognitionManager.this.mCancelRequested = true;
                    VoiceRecognitionManager.this.mSpeechRecognizer.cancel();
                    break;
                case 8:
                    VoiceRecognitionManager.this.mCancelRequested = true;
                    VoiceRecognitionManager.this.mSpeechRecognizer.cancel();
                    break;
                case 9:
                    if (VoiceRecognitionManager.this.mController.isViewShowing()) {
                        VoiceRecognitionManager.this.mCallback.onPermissionNeeded();
                        break;
                    }
                    break;
                case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                    // The two codes that actually mean "not this language". Google's service never
                    // sends them (it falls back silently), but other recognisers do; before this they
                    // just stopped the mic with no message.
                    VoiceRecognitionManager.this.mCallback.onLanguageNotSupported(VoiceRecognitionManager.this.mLastLanguageTag);
                    break;
            }
        }

        @Override // android.speech.RecognitionListener
        public void onEvent(int i, Bundle bundle) {
            Logger.debug(VoiceRecognitionManager.TAG, "onEvent");
            Logger.debug(VoiceRecognitionManager.TAG, "Event type: " + i + "bundle: " + bundle.toString());
        }

        @Override // android.speech.RecognitionListener
        public void onPartialResults(Bundle bundle) {
            Logger.debug(VoiceRecognitionManager.TAG, "onPartialResults");
        }

        @Override // android.speech.RecognitionListener
        public void onReadyForSpeech(Bundle bundle) {
            Logger.debug(VoiceRecognitionManager.TAG, "onReadyForSpeech");
            VoiceRecognitionManager.this.mReadyForSpeech = true;
            VoiceRecognitionManager.this.notifyState(STATE_READY_FOR_SPEECH);
        }

        @Override // android.speech.RecognitionListener
        public void onResults(Bundle bundle) {
            Logger.debug(VoiceRecognitionManager.TAG, "onResults");
            ArrayList<String> stringArrayList =
                    bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            Logger.debug(VoiceRecognitionManager.TAG, stringArrayList != null ? stringArrayList.toString() : "Result matches were null");
            if (stringArrayList != null && stringArrayList.size() > 0) {
                String str = stringArrayList.get(0);
                if (VoiceRecognitionManager.this.mMode == Mode.DICTATION) {
                    VoiceRecognitionManager.this.mCallback.onDictationResult(str);
                }
            }
            VoiceRecognitionManager.this.mReadyForSpeech = false;
            VoiceRecognitionManager.this.mMode = Mode.NONE;
            VoiceRecognitionManager.this.notifyState(STATE_STOPPED);
        }

        @Override // android.speech.RecognitionListener
        public void onRmsChanged(float f) {
            Logger.debug(VoiceRecognitionManager.TAG, "onRmsChanged:" + f);
            // Drives the Material waveform; no-op under Classic/Modern.
            VoiceRecognitionManager.this.mController.onAudioLevel(f);
        }
    }
}
