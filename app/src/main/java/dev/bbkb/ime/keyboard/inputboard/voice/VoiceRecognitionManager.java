package dev.bbkb.ime.keyboard.inputboard.voice;

import dev.bbkb.ime.BuildConfig;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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

    /** Recognition lifecycle states passed to {@link Callback#onStateChanged}. */
    public static final int STATE_STARTED = 1;

    public static final int STATE_READY_FOR_SPEECH = 2;

    public static final int STATE_STOPPED = 3;

    private SpeechRecognizer mSpeechRecognizer;

    /** What the "Speech recognizer" setting resolved to when {@link #mSpeechRecognizer} was built. */
    private VoiceRecognizerChoice.Selection mSelection;

    private Callback mCallback;

    private VoiceInputController mController;

    private Mode mMode;

    private final Handler mHandler;

    private final Runnable mTimeoutRunnable;

    private boolean mReadyForSpeech;

    /** Set when we cancel deliberately, so the resulting ERROR_CLIENT is ignored. */
    private boolean mCancelRequested;

    /** A stop or cancel was still unanswered at session start: an early ERROR_CLIENT may be its answer. */
    private boolean mStaleClientErrorPossible;

    /** {@link #stopDictation} sent stopListening(); the final result is awaited up to {@link #STOP_GRACE_MILLIS}. */
    private boolean mStopRequested;

    /**
     * "Stop listening when you pause" is off: on Android 13+ a segmented session hands phrases
     * over without ending; elsewhere, and whenever a session ends on its own, the next one starts
     * at once ({@link #chain}).
     */
    private boolean mContinuous;

    /** Continuous mode: whether the running session has committed anything yet. */
    private boolean mCommittedThisSession;

    /** Continuous mode: sessions in a row that heard nothing. */
    private int mEmptySessionsInARow;

    /** The last dictation request, replayed by {@link #chain}. */
    private Intent mDictationIntent;

    /** Posted by {@link #stopDictation}: a stop the recogniser never answered is cancelled. */
    private final Runnable mFinaliseRunnable;

    /** The listener given to the recogniser; kept so a test can play the recogniser's side. */
    private RecognitionListener mListener;

    /** How long a stop waits for the final result before giving up on it. */
    static final long STOP_GRACE_MILLIS = 2000L;

    /**
     * Idle time before the recogniser is destroyed. While it exists it stays bound to its speech
     * service, which keeps that app alive; the next dictation builds a new one.
     */
    static final long IDLE_RELEASE_MILLIS = 30_000L;

    /** Started with each session and re-posted while one runs: release is never mid-session. */
    private final Runnable mReleaseRunnable;

    /** {@link #destroy} has run: no recogniser is built again. */
    private boolean mDestroyed;

    /** With "stop when you pause" on: the silence to allow before ending (a hint; recognisers cap it). */
    static final long PAUSE_SILENCE_MILLIS = 2500L;

    /** Continuous mode on Android 13+: the silence after which a segmented session ends on its own. */
    static final long CONTINUOUS_SILENCE_MILLIS = 30_000L;

    /** Continuous mode: silent sessions in a row after which listening ends rather than chaining again. */
    static final int MAX_EMPTY_SESSIONS = 3;

    /** Application context. */
    private final Context mContext;

    /** The language the last dictation was for; what the error and the chip should name. */
    private String mLastLanguageTag = SettingsManager.DEFAULT_VOICE_INPUT_LANGUAGE;

    /**
     * Tag asked for to the tag this recogniser accepts ("fr" to "fr-FR"), learned by
     * {@link #resolveRefusedLanguage} and cleared when the recogniser changes.
     */
    private final java.util.HashMap<String, String> mUsableTags = new java.util.HashMap<>();

    /** Whether the dictation the user started has already had its one language check. */
    private boolean mLanguageChecked;

    /** Whether the dictation the user started has already been retried with a region added. */
    private boolean mRegionTried;

    /** Counts user-started dictations; a language check or download answer from an older one is ignored. */
    private int mLanguageRequest;

    /** Uptime until which a followed download keeps the idle recogniser (its listener would be dropped). */
    private long mDownloadHoldUntil;

    /** The current download's listener, for a test to play the recogniser's side. */
    private Object mDownloadListener;

    /** How long a recogniser is kept for a download that has not reported back. */
    static final long DOWNLOAD_HOLD_MILLIS = 10 * 60_000L;

    /**
     * Silence after which a download request counts as refused: a recogniser with the feature off
     * drops it silently (Google logs "disabled via flag. Noop").
     */
    static final long DOWNLOAD_ANSWER_MILLIS = 20_000L;

    /** Reports a download request that nothing answered; posted by {@link #downloadLanguage}. */
    private Runnable mDownloadUnanswered;

    
    public enum Mode {
        DICTATION,
        NONE
    }

    
    public interface Callback {
        /**
         * @param mode the current mode
         * @param state one of the STATE_ constants
         */
        void onStateChanged(Mode mode, int state);

        /** @param text the final transcription */
        void onDictationResult(String text);

        void onPermissionNeeded();

        /** The chosen recognition app was refused the microphone; {@code appLabel} names it. */
        void onRecognizerNeedsPermission(String appLabel);

        /** The chosen recognition app has the microphone but would not serve this keyboard. */
        void onRecognizerRefused(String appLabel);

        void onLanguageUnavailable();

        /** Error 12 or 13; {@code languageTag} is what was sent. */
        void onLanguageNotSupported(String languageTag);

        /**
         * Dictation starts by itself when the download finishes, if the board is still up.
         * @param percent 0 to 100, or -1 when not known
         */
        void onLanguageDownloading(String languageTag, int percent);

        /** The download was accepted but will happen later, or cannot be followed (Android 13). */
        void onLanguageDownloadPending(String languageTag);

        /** No speech-recognition service to bind. */
        void onNoRecognitionService();

        /** A session ended on an error worth showing. */
        void onRecognitionError(int messageRes);

        void onPartialResult(String text);

        /** No final result: the partial text showing stays as it is. */
        void onDictationAbandoned();
    }

    /** @param voiceInputController the board's controller, also the callback */
    public VoiceRecognitionManager(Context context, VoiceInputController voiceInputController) {
        this.mContext = context.getApplicationContext();
        this.mSelection = InstalledVoiceRecognizers.resolve(this.mContext);
        this.mSpeechRecognizer = InstalledVoiceRecognizers.create(this.mContext, this.mSelection);
        this.mListener = new RecognitionListenerImpl();
        this.mSpeechRecognizer.setRecognitionListener(this.mListener);
        this.mCallback = voiceInputController;
        this.mController = voiceInputController;
        this.mMode = Mode.NONE;
        this.mHandler = new Handler(android.os.Looper.getMainLooper());
        this.mTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (!VoiceRecognitionManager.this.mReadyForSpeech || VoiceRecognitionManager.this.mMode == Mode.NONE) {
                    return;
                }
                VoiceRecognitionManager.this.stopListening();
            }
        };
        this.mFinaliseRunnable = new Runnable() {
            @Override
            public void run() {
                if (VoiceRecognitionManager.this.mMode == Mode.NONE || !VoiceRecognitionManager.this.mStopRequested) {
                    return;
                }
                Logger.debug(TAG, "No final result after the stop; cancelling");
                VoiceRecognitionManager.this.stopListening();
            }
        };
        this.mReleaseRunnable = new Runnable() {
            @Override
            public void run() {
                if (VoiceRecognitionManager.this.mSpeechRecognizer == null) {
                    return;
                }
                if (VoiceRecognitionManager.this.mMode != Mode.NONE
                        || android.os.SystemClock.uptimeMillis() < VoiceRecognitionManager.this.mDownloadHoldUntil) {
                    VoiceRecognitionManager.this.mHandler.postDelayed(this, IDLE_RELEASE_MILLIS);
                    return;
                }
                Logger.debug(TAG, "Idle: releasing the speech recogniser");
                // destroy() drops the listener: nothing it still had queued can arrive later.
                VoiceRecognitionManager.this.mSpeechRecognizer.destroy();
                VoiceRecognitionManager.this.mSpeechRecognizer = null;
                VoiceRecognitionManager.this.mCancelRequested = false;
            }
        };
    }

    RecognitionListener listenerForTest() {
        return this.mListener;
    }

    public void startDictation() {
        Logger.debug(TAG, "Dictation button pressed");
        // A fresh language check; answers to earlier checks or downloads are now stale.
        this.mLanguageChecked = false;
        this.mRegionTried = false;
        this.mLanguageRequest++;
        this.mDownloadHoldUntil = 0L;
        forgetDownloadWatch();
        beginDictation();
    }

    /** Builds the dictation request from the current settings and starts listening. */
    private void beginDictation() {
        refreshRecognizer();
        SettingsValues c0804dM5050c = SettingsManager.getInstance().getSettingsValues();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        // Google's service treats a raw subtype locale ("en_US", "zh_CN_pinyin") as invalid and
        // silently uses the phone's language; it needs a hyphenated BCP-47 tag.
        final SubtypeManager subtypes = SubtypeManager.getInstance();
        final String keyboardLocale = subtypes.getCurrentSubtype() != null ? subtypes.getCurrentSubtype().getLocale() : null;
        final String tag = VoiceLanguageTags.effectiveTag(c0804dM5050c.voiceInputUseInputLanguage, keyboardLocale, c0804dM5050c.voiceInputLanguageList);
        this.mLastLanguageTag = tag;
        final String known = this.mUsableTags.get(tag);
        final String sentTag = known != null ? known : tag;
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, sentTag);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, sentTag);
        if (c0804dM5050c.voiceInputUseInputLanguage && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Let the recogniser detect and switch among a multi-language keyboard's languages.
            final ArrayList<String> allowed = VoiceLanguageTags.allowedTags(sentTag, subtypes.getCurrentSubtypeAdditionalLocales());
            if (allowed.size() > 1) {
                intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
                intent.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, allowed);
                intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, true);
                intent.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, allowed);
            }
        }
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, c0804dM5050c.voiceInputPreferOffline);
        // Without this extra the recogniser always masks, whatever "Block offensive words" says.
        intent.putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, c0804dM5050c.blockPotentiallyOffensiveWords);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        // Recognisers attribute (and on some ROMs permission-check) the request by this package.
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, this.mContext.getPackageName());
        // "Stop listening when you pause" on: one utterance with a longer pause hint. Off: on
        // Android 13+ a segmented session (onSegmentResults, ends after CONTINUOUS_SILENCE_MILLIS
        // or our stop); older Android chains plain sessions from onResults (see chain()).
        final boolean stopOnPause = c0804dM5050c.voiceInputStopOnPause;
        this.mContinuous = !stopOnPause;
        this.mEmptySessionsInARow = 0;
        if (stopOnPause) {
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, PAUSE_SILENCE_MILLIS);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, CONTINUOUS_SILENCE_MILLIS);
        }
        this.mDictationIntent = intent;
        startRecognition(Mode.DICTATION, intent);
    }

    /**
     * The on-screen mic while listening. Unlike {@link #stopListening} it keeps what was heard:
     * the final result commits as usual, and only a stop never answered within
     * {@link #STOP_GRACE_MILLIS} is cancelled.
     */
    public void stopDictation() {
        if (this.mSpeechRecognizer == null || this.mMode == Mode.NONE || this.mStopRequested) {
            return;
        }
        Logger.debug(TAG, "Stop requested; waiting for the final result");
        this.mStopRequested = true;
        this.mHandler.removeCallbacks(this.mTimeoutRunnable);
        this.mSpeechRecognizer.stopListening();
        this.mHandler.removeCallbacks(this.mFinaliseRunnable);
        this.mHandler.postDelayed(this.mFinaliseRunnable, STOP_GRACE_MILLIS);
    }

    /** Whether the running session is a continuous one (the board says "tap to stop"). */
    public boolean isContinuous() {
        return this.mContinuous && this.mMode != Mode.NONE;
    }

    public void stopListening() {
        if (this.mSpeechRecognizer == null) {
            return;
        }
        Logger.debug(TAG, "Stopped listening to mode " + this.mMode);
        this.mHandler.removeCallbacks(this.mTimeoutRunnable);
        this.mHandler.removeCallbacks(this.mFinaliseRunnable);
        this.mStopRequested = false;
        this.mContinuous = false;
        // cancel() fires an async onError(ERROR_CLIENT); mark it deliberate so the transient-error
        // handling does not re-arm mMode (a phantom session would make every tap a stop).
        this.mCancelRequested = true;
        this.mSpeechRecognizer.stopListening();
        this.mSpeechRecognizer.cancel();
        this.mMode = Mode.NONE;
        this.mCallback.onDictationAbandoned();
        notifyState(STATE_STOPPED);
    }

    /**
     * Continuous mode: chain unless the user stopped, the board closed, or the last
     * {@link #MAX_EMPTY_SESSIONS} sessions were silent (a forgotten mic is not kept open).
     */
    private boolean shouldChain() {
        return this.mContinuous && !this.mStopRequested && this.mSpeechRecognizer != null
                && this.mController.isViewShowing() && this.mEmptySessionsInARow < MAX_EMPTY_SESSIONS;
    }

    private void chain() {
        Logger.debug(TAG, "Continuous: next session (silent in a row: " + this.mEmptySessionsInARow + ")");
        startRecognition(Mode.DICTATION, this.mDictationIntent);
    }

    /** Clears the per-session flags once a session ends for good. */
    private void endSession() {
        this.mHandler.removeCallbacks(this.mTimeoutRunnable);
        this.mHandler.removeCallbacks(this.mFinaliseRunnable);
        this.mStopRequested = false;
        this.mContinuous = false;
        this.mMode = Mode.NONE;
        this.mReadyForSpeech = false;
    }

    /** Counts the session that just ended toward the silent-sessions cap. */
    private void noteSessionOutcome() {
        this.mEmptySessionsInARow = this.mCommittedThisSession ? 0 : this.mEmptySessionsInARow + 1;
    }

    /** Commits the first result, or abandons the partial when there is none; true if committed. */
    private boolean commitResult(Bundle bundle) {
        ArrayList<String> stringArrayList = bundle == null ? null
                : bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        Logger.debug(TAG, stringArrayList != null ? stringArrayList.toString() : "Result matches were null");
        if (stringArrayList != null && !stringArrayList.isEmpty()) {
            String str = stringArrayList.get(0);
            if (this.mMode == Mode.DICTATION && str != null && !str.isEmpty()) {
                this.mCallback.onDictationResult(str);
                this.mCommittedThisSession = true;
                return true;
            }
        }
        this.mCallback.onDictationAbandoned();
        return false;
    }

    /**
     * Nulls the recogniser and cancels the timeouts: closeBoard calls cancelVoiceInput() after
     * VoiceInputController.destroy() has run.
     */
    public void destroy() {
        this.mDestroyed = true;
        this.mLanguageRequest++;
        this.mDownloadHoldUntil = 0L;
        forgetDownloadWatch();
        this.mHandler.removeCallbacks(this.mTimeoutRunnable);
        this.mHandler.removeCallbacks(this.mReleaseRunnable);
        SpeechRecognizer speechRecognizer = this.mSpeechRecognizer;
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
            speechRecognizer.destroy();
            this.mSpeechRecognizer = null;
        }
        this.mMode = Mode.NONE;
        this.mReadyForSpeech = false;
    }

    /**
     * Before each dictation: rebuilds the recogniser when the "Speech recognizer" setting resolves
     * to another app, or after an idle release ({@link #mReleaseRunnable}). Never after destroy.
     */
    private void refreshRecognizer() {
        if (this.mDestroyed) {
            return;
        }
        final VoiceRecognizerChoice.Selection selection = InstalledVoiceRecognizers.resolve(this.mContext);
        if (this.mSpeechRecognizer != null) {
            if (selection.equals(this.mSelection)) {
                return;
            }
            Logger.debug(TAG, "Speech recognizer changed: " + this.mSelection + " -> " + selection);
            // destroy() drops the old listener, so nothing it still had queued reaches this session.
            this.mSpeechRecognizer.destroy();
        }
        this.mCancelRequested = false;
        if (!selection.equals(this.mSelection)) {
            this.mUsableTags.clear();  // another recogniser names languages its own way
        }
        this.mSelection = selection;
        this.mSpeechRecognizer = InstalledVoiceRecognizers.create(this.mContext, selection);
        this.mListener = new RecognitionListenerImpl();
        this.mSpeechRecognizer.setRecognitionListener(this.mListener);
    }

    private boolean hasOwnMicrophonePermission() {
        return this.mContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** Null when it cannot be checked; the package is visible via the manifest's RecognitionService query. */
    private Boolean appMicrophonePermission(VoiceRecognizerChoice.Selection selection) {
        if (selection == null || selection.packageName == null) {
            return null;
        }
        try {
            return this.mContext.getPackageManager().checkPermission(Manifest.permission.RECORD_AUDIO,
                    selection.packageName) == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** @param mode the mode the session runs in */
    private void startRecognition(Mode mode, Intent intent) {
        if (this.mSpeechRecognizer == null) {
            return;
        }
        this.mMode = mode;
        this.mReadyForSpeech = false;
        this.mStopRequested = false;
        this.mCommittedThisSession = false;
        this.mHandler.removeCallbacks(this.mFinaliseRunnable);
        this.mStaleClientErrorPossible = this.mCancelRequested;
        this.mCancelRequested = false;
        notifyState(STATE_STARTED);
        this.mSpeechRecognizer.startListening(intent);
        this.mHandler.removeCallbacks(this.mReleaseRunnable);
        this.mHandler.postDelayed(this.mReleaseRunnable, IDLE_RELEASE_MILLIS);
        this.mHandler.removeCallbacks(this.mTimeoutRunnable);
        // The 6 s nothing-heard cut-off is for single utterances; continuous mode relies on the
        // recogniser's no-speech timeout and the silent-sessions cap in shouldChain().
        if (!this.mContinuous) {
            this.mHandler.postDelayed(this.mTimeoutRunnable, 6000L);
        }
    }

    public Mode getMode() {
        return this.mMode;
    }

    /**
     * Before telling the user of a refused language, tries once per user dictation (so it cannot
     * loop): a bare tag refused as unknown is retried with its likely region (Google knows
     * "fr-FR", not "fr"); then on Android 13+ the recogniser is asked what it has, using another
     * tag or downloading. One that cannot answer is still asked to download on 14+ when the
     * refusal was "not downloaded".
     * @return false when nothing was started and the caller should report the refusal
     */
    private boolean resolveRefusedLanguage(final int error) {
        if (this.mSpeechRecognizer == null || this.mDictationIntent == null) {
            return false;
        }
        final String asked = this.mLastLanguageTag;
        final String refused = this.mDictationIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED && retryWithLikelyRegion(asked, refused)) {
            return true;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || this.mLanguageChecked) {
            return false;
        }
        this.mLanguageChecked = true;
        final int request = this.mLanguageRequest;
        try {
            VoiceLanguageSupport.check(this.mSpeechRecognizer, this.mDictationIntent, refused,
                    this.mContext.getMainExecutor(), new VoiceLanguageSupport.CheckListener() {
                        @Override
                        public void onDecision(VoiceLanguageSupport.Decision decision) {
                            if (isCurrentLanguageRequest(request)) {
                                onLanguageDecision(request, asked, decision);
                            }
                        }

                        @Override
                        public void onCheckFailed() {
                            if (!isCurrentLanguageRequest(request)) {
                                return;
                            }
                            if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                // Ask for exactly what it refused. 14+ only: on 13 a recogniser
                                // that cannot list its languages silently drops the request.
                                downloadLanguage(request, asked, refused);
                            } else {
                                VoiceRecognitionManager.this.mCallback.onLanguageNotSupported(asked);
                            }
                        }
                    });
        } catch (RuntimeException e) {
            Logger.debug(TAG, "Language support check failed: " + e);
            return false;
        }
        return true;
    }

    /** Restarts with "fr" as "fr-FR" and remembers the fuller tag; true if restarted. */
    private boolean retryWithLikelyRegion(String asked, String refused) {
        if (this.mRegionTried || refused == null || refused.indexOf('-') >= 0) {
            return false;
        }
        final String region = VoiceLanguageTags.likelyRegion(refused);
        if (region == null) {
            return false;
        }
        this.mRegionTried = true;
        Logger.debug(TAG, "Language " + refused + " refused; trying " + refused + "-" + region);
        this.mUsableTags.put(asked, refused + "-" + region);
        if (this.mMode != Mode.NONE || !this.mController.isViewShowing()) {
            return false;
        }
        beginDictation();
        return true;
    }

    private boolean isCurrentLanguageRequest(int request) {
        return request == this.mLanguageRequest && !this.mDestroyed;
    }

    private void onLanguageDecision(int request, String asked, VoiceLanguageSupport.Decision decision) {
        Logger.debug(TAG, "Language " + asked + ": " + decision);
        switch (decision.kind) {
            case USE:
                this.mUsableTags.put(asked, decision.tag);
                if (this.mMode == Mode.NONE && this.mController.isViewShowing()) {
                    beginDictation();
                }
                break;
            case PENDING:
            case DOWNLOAD:
                downloadLanguage(request, asked, decision.tag);
                break;
            default:
                this.mCallback.onLanguageNotSupported(asked);
                break;
        }
    }

    /** Requests the download of {@code tag} and follows it; see {@link VoiceLanguageSupport#download}. */
    private void downloadLanguage(final int request, final String asked, final String tag) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || this.mSpeechRecognizer == null) {
            this.mCallback.onLanguageNotSupported(asked);
            return;
        }
        final Intent intent = new Intent(this.mDictationIntent);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag);
        final VoiceLanguageSupport.DownloadListener listener = new VoiceLanguageSupport.DownloadListener() {
            @Override
            public void onProgress(int percent) {
                if (isCurrentLanguageRequest(request)) {
                    forgetDownloadWatch();
                    VoiceRecognitionManager.this.mCallback.onLanguageDownloading(asked, percent);
                }
            }

            @Override
            public void onSuccess() {
                if (!isCurrentLanguageRequest(request)) {
                    return;
                }
                forgetDownloadWatch();
                VoiceRecognitionManager.this.mDownloadHoldUntil = 0L;
                VoiceRecognitionManager.this.mUsableTags.put(asked, tag);
                if (VoiceRecognitionManager.this.mMode == Mode.NONE
                        && VoiceRecognitionManager.this.mController.isViewShowing()) {
                    beginDictation();
                }
            }

            @Override
            public void onScheduled() {
                if (isCurrentLanguageRequest(request)) {
                    forgetDownloadWatch();
                    VoiceRecognitionManager.this.mDownloadHoldUntil = 0L;
                    VoiceRecognitionManager.this.mCallback.onLanguageDownloadPending(asked);
                }
            }

            @Override
            public void onError() {
                if (isCurrentLanguageRequest(request)) {
                    forgetDownloadWatch();
                    VoiceRecognitionManager.this.mDownloadHoldUntil = 0L;
                    VoiceRecognitionManager.this.mCallback.onLanguageNotSupported(asked);
                }
            }
        };
        this.mDownloadListener = listener;
        this.mDownloadHoldUntil = android.os.SystemClock.uptimeMillis() + DOWNLOAD_HOLD_MILLIS;
        this.mCallback.onLanguageDownloading(asked, -1);
        forgetDownloadWatch();
        this.mDownloadUnanswered = new Runnable() {
            @Override
            public void run() {
                Logger.debug(TAG, "Language download request went unanswered");
                listener.onError();
            }
        };
        this.mHandler.postDelayed(this.mDownloadUnanswered, DOWNLOAD_ANSWER_MILLIS);
        try {
            VoiceLanguageSupport.download(this.mSpeechRecognizer, intent, this.mContext.getMainExecutor(), listener);
        } catch (RuntimeException e) {
            Logger.debug(TAG, "Language download request failed: " + e);
            listener.onError();
        }
    }

    /** Stops waiting for a download request to be answered. */
    private void forgetDownloadWatch() {
        if (this.mDownloadUnanswered != null) {
            this.mHandler.removeCallbacks(this.mDownloadUnanswered);
            this.mDownloadUnanswered = null;
        }
    }

    Object languageDownloadListenerForTest() {
        return this.mDownloadListener;
    }

    /** The tag the last dictation request was sent with. */
    public String getLastLanguageTag() {
        return this.mLastLanguageTag;
    }

    /** @param state one of the STATE_ constants */
    public void notifyState(int state) {
        this.mCallback.onStateChanged(this.mMode, state);
    }

    /**
     * "Show words as you speak", read per partial so a change applies without a restart. Partials
     * are always requested; only forwarding them to the editor depends on this.
     */
    private static boolean showsPartialResults() {
        SettingsValues settingsValues = SettingsManager.getInstance().getSettingsValues();
        return settingsValues == null || settingsValues.voiceInputShowPartialResults;
    }

    
    protected class RecognitionListenerImpl implements RecognitionListener {
        /** @param buffer raw audio, unused */
        @Override
        public void onBufferReceived(byte[] buffer) {
            // Raw audio is not needed.
        }

        protected RecognitionListenerImpl() {
            // Stateless; reads the outer manager's fields.
        }

        @Override
        public void onBeginningOfSpeech() {
            Logger.info(VoiceRecognitionManager.TAG, "onBeginningOfSpeech");
            VoiceRecognitionManager.this.mHandler.removeCallbacks(VoiceRecognitionManager.this.mTimeoutRunnable);
        }

        @Override
        public void onEndOfSpeech() {
            Logger.debug(VoiceRecognitionManager.TAG, "onEndOfSpeech, ended mode = " + VoiceRecognitionManager.this.mMode);
            VoiceRecognitionManager.this.mReadyForSpeech = false;
            VoiceRecognitionManager.this.notifyState(STATE_STOPPED);
        }

        /** @param error the SpeechRecognizer ERROR_ code (5 is ERROR_CLIENT) */
        @Override
        public void onError(int error) {
            Logger.debug(VoiceRecognitionManager.TAG, "error = " + error);
            final boolean sessionActive = VoiceRecognitionManager.this.mMode != Mode.NONE;
            final boolean noService = VoiceRecognitionAvailability.isNoServiceError(error, sessionActive,
                    VoiceRecognitionManager.this.mReadyForSpeech, VoiceRecognitionManager.this.mStaleClientErrorPossible);
            // Restored below in the one case where the session has not ended (transient ERROR_CLIENT).
            final boolean stopRequested = VoiceRecognitionManager.this.mStopRequested;
            final boolean continuous = VoiceRecognitionManager.this.mContinuous;
            VoiceRecognitionManager.this.mMode = Mode.NONE;
            VoiceRecognitionManager.this.mReadyForSpeech = false;
            VoiceRecognitionManager.this.mStopRequested = false;
            VoiceRecognitionManager.this.mContinuous = false;
            VoiceRecognitionManager.this.mHandler.removeCallbacks(VoiceRecognitionManager.this.mFinaliseRunnable);
            // Deliberate stop/cancel: skip the transient-error handling (the case-5 re-arm).
            if (VoiceRecognitionManager.this.mCancelRequested) {
                VoiceRecognitionManager.this.mCancelRequested = false;
                if (error != 5) {
                    VoiceRecognitionManager.this.notifyState(STATE_STOPPED);
                }
                return;
            }
            if (error == 5 && !sessionActive) {
                // Nothing was running: the answer to a stray stop (or a second missing-service
                // report). Re-arming here would create a phantom session.
                return;
            }
            if (error == 5) {
                // Whatever this ERROR_CLIENT answered, the next one is this session's own.
                VoiceRecognitionManager.this.mStaleClientErrorPossible = false;
            }
            if (noService) {
                // No service to bind: must not re-arm, and must send STOPPED.
                VoiceRecognitionManager.this.mCallback.onDictationAbandoned();
                VoiceRecognitionManager.this.notifyState(STATE_STOPPED);
                VoiceRecognitionManager.this.mCallback.onNoRecognitionService();
                return;
            }
            if (error == 5 && VoiceRecognitionManager.this.mController.isViewShowing()) {
                // Transient: the answer to an earlier stop, arriving after this session started.
                // The session is still live, so its partial text stays composing.
                VoiceRecognitionManager.this.mMode = Mode.DICTATION;
                VoiceRecognitionManager.this.mStopRequested = stopRequested;
                VoiceRecognitionManager.this.mContinuous = continuous;
                return;
            }
            VoiceRecognitionManager.this.mCallback.onDictationAbandoned();
            final boolean nothingHeard = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH;
            if (nothingHeard && continuous && !stopRequested && sessionActive) {
                // Continuous: a silent session is a pause; chain quietly until the silent cap.
                VoiceRecognitionManager.this.mContinuous = true;
                VoiceRecognitionManager.this.noteSessionOutcome();
                if (VoiceRecognitionManager.this.shouldChain()) {
                    VoiceRecognitionManager.this.chain();
                    return;
                }
                VoiceRecognitionManager.this.mContinuous = false;
            }
            if (error != 5) {
                VoiceRecognitionManager.this.notifyState(STATE_STOPPED);
            }
            // A stop with nothing said: the user ended it, so "No speech heard" is noise.
            final int message = nothingHeard && stopRequested
                    ? VoiceRecognitionAvailability.NO_MESSAGE : VoiceRecognitionAvailability.messageForError(error);
            if (message != VoiceRecognitionAvailability.NO_MESSAGE) {
                VoiceRecognitionManager.this.mCallback.onRecognitionError(message);
            }
            switch (error) {
                case 4:
                    VoiceRecognitionManager.this.mCallback.onLanguageUnavailable();
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
                        // With a chosen app, asking for our own permission again cannot help.
                        final VoiceRecognizerChoice.Selection selection = VoiceRecognitionManager.this.mSelection;
                        final boolean ownGranted = VoiceRecognitionManager.this.hasOwnMicrophonePermission();
                        switch (VoiceRecognizerChoice.permissionErrorAction(selection, ownGranted,
                                ownGranted ? VoiceRecognitionManager.this.appMicrophonePermission(selection) : null)) {
                            case APP_NEEDS_PERMISSION:
                                VoiceRecognitionManager.this.mCallback.onRecognizerNeedsPermission(VoiceRecognizerChoice.appName(selection));
                                break;
                            case APP_REFUSED:
                                VoiceRecognitionManager.this.mCallback.onRecognizerRefused(VoiceRecognizerChoice.appName(selection));
                                break;
                            default:
                                VoiceRecognitionManager.this.mCallback.onPermissionNeeded();
                                break;
                        }
                    }
                    break;
                case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                    // Google's online service never sends these (it falls back silently); on-device
                    // recognisers do. First try another tag or a download.
                    if (!VoiceRecognitionManager.this.resolveRefusedLanguage(error)) {
                        VoiceRecognitionManager.this.mCallback.onLanguageNotSupported(VoiceRecognitionManager.this.mLastLanguageTag);
                    }
                    break;
            }
        }

        /** @param eventType the recogniser's event type */
        @Override
        public void onEvent(int eventType, Bundle bundle) {
            Logger.debug(VoiceRecognitionManager.TAG, "onEvent");
            Logger.debug(VoiceRecognitionManager.TAG, "Event type: " + eventType + "bundle: " + bundle.toString());
        }

        @Override
        public void onPartialResults(Bundle bundle) {
            Logger.debug(VoiceRecognitionManager.TAG, "onPartialResults");
            if (VoiceRecognitionManager.this.mMode != Mode.DICTATION || VoiceRecognitionManager.this.mCancelRequested) {
                return;
            }
            if (!showsPartialResults()) {
                // "Show words as you speak" is off: only onResults reaches the editor.
                return;
            }
            ArrayList<String> partials = bundle == null ? null
                    : bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (partials != null && !partials.isEmpty() && partials.get(0) != null) {
                VoiceRecognitionManager.this.mCallback.onPartialResult(partials.get(0));
            }
        }

        @Override
        public void onReadyForSpeech(Bundle bundle) {
            Logger.debug(VoiceRecognitionManager.TAG, "onReadyForSpeech");
            VoiceRecognitionManager.this.mReadyForSpeech = true;
            VoiceRecognitionManager.this.notifyState(STATE_READY_FOR_SPEECH);
        }

        @Override
        public void onResults(Bundle bundle) {
            Logger.debug(VoiceRecognitionManager.TAG, "onResults");
            VoiceRecognitionManager.this.commitResult(bundle);
            VoiceRecognitionManager.this.mReadyForSpeech = false;
            VoiceRecognitionManager.this.noteSessionOutcome();
            if (VoiceRecognitionManager.this.shouldChain()) {
                // Continuous without segmented sessions (or one that ended anyway): next session.
                VoiceRecognitionManager.this.chain();
                return;
            }
            VoiceRecognitionManager.this.endSession();
            VoiceRecognitionManager.this.notifyState(STATE_STOPPED);
        }

        @Override
        public void onSegmentResults(Bundle bundle) {
            // Continuous mode on Android 13+: one phrase of a session that goes on.
            Logger.debug(VoiceRecognitionManager.TAG, "onSegmentResults");
            if (VoiceRecognitionManager.this.mMode != Mode.DICTATION) {
                return;
            }
            if (VoiceRecognitionManager.this.commitResult(bundle)) {
                VoiceRecognitionManager.this.mEmptySessionsInARow = 0;
            }
        }

        @Override
        public void onEndOfSegmentedSession() {
            // Our stop or CONTINUOUS_SILENCE_MILLIS of silence; chained like a plain session.
            Logger.debug(VoiceRecognitionManager.TAG, "onEndOfSegmentedSession");
            if (VoiceRecognitionManager.this.mMode == Mode.NONE) {
                return;
            }
            VoiceRecognitionManager.this.mReadyForSpeech = false;
            VoiceRecognitionManager.this.noteSessionOutcome();
            if (VoiceRecognitionManager.this.shouldChain()) {
                VoiceRecognitionManager.this.chain();
                return;
            }
            VoiceRecognitionManager.this.endSession();
            VoiceRecognitionManager.this.notifyState(STATE_STOPPED);
        }

        /** @param rmsdB the input level in dB */
        @Override
        public void onRmsChanged(float rmsdB) {
            // 10 to 50 times a second: skip the string concatenation outside debug builds.
            if (BuildConfig.DEBUG) {
                Logger.debug(VoiceRecognitionManager.TAG, "onRmsChanged:" + rmsdB);
            }
            // Drives the Material waveform; no-op under Classic/Modern.
            VoiceRecognitionManager.this.mController.onAudioLevel(rmsdB);
        }
    }
}
