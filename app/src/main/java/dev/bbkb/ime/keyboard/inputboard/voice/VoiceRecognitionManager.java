package dev.bbkb.ime.keyboard.inputboard.voice;

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

    /**
     * Recognition lifecycle states passed to {@link Callback#onStateChanged}. These
     * were bare 1/2/3 literals, decoded on the far side with an opaque
     * {@code (i & 1) != 0} bit test (audit IB-16).
     */
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

    /**
     * A stop or cancel of ours was still unanswered when the current session started, so an
     * ERROR_CLIENT before onReadyForSpeech may be that stop's answer rather than this session's.
     */
    private boolean mStaleClientErrorPossible;

    /**
     * The user asked to finish ({@link #stopDictation}): {@code stopListening()} was sent and the
     * final result is awaited, for up to {@link #STOP_GRACE_MILLIS}.
     */
    private boolean mStopRequested;

    /**
     * "Stop listening when you pause" is off for this dictation: the session is kept going through
     * pauses until the user stops it. On Android 13+ the recogniser is asked for a segmented
     * session, which hands phrases over as they finish without ending; elsewhere, and whenever a
     * session ends on its own, the next one is started straight away ({@link #chain}).
     */
    private boolean mContinuous;

    /** Continuous mode: whether the running session has committed anything yet. */
    private boolean mCommittedThisSession;

    /** Continuous mode: sessions in a row that ended with nothing heard. {@link #MAX_EMPTY_SESSIONS} ends listening. */
    private int mEmptySessionsInARow;

    /** The last dictation request, replayed by {@link #chain}. */
    private Intent mDictationIntent;

    /** Posted by {@link #stopDictation}: a stop the recogniser never answered is cancelled. */
    private final Runnable mFinaliseRunnable;

    /** The listener given to the recogniser; kept so a test can play the recogniser's side. */
    private RecognitionListener mListener;

    /** How long a stop waits for the final result before giving up on it. */
    static final long STOP_GRACE_MILLIS = 2000L;

    /** With "stop when you pause" on: the silence the recogniser is asked to allow before ending. A hint; recognisers cap it. */
    static final long PAUSE_SILENCE_MILLIS = 2500L;

    /** Continuous mode on Android 13+: the silence after which a segmented session ends on its own. */
    static final long CONTINUOUS_SILENCE_MILLIS = 30_000L;

    /** Continuous mode: silent sessions in a row after which listening ends rather than chaining again. */
    static final int MAX_EMPTY_SESSIONS = 3;

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

        /** The chosen recognition app was refused the microphone; {@code appLabel} names it. */
        void onRecognizerNeedsPermission(String appLabel);

        /** The chosen recognition app has the microphone but would not serve this keyboard. */
        void onRecognizerRefused(String appLabel);

        void onLanguageUnavailable();

        /** The recogniser refused the language itself (error 12 or 13); {@code languageTag} is what was sent. */
        void onLanguageNotSupported(String languageTag);

        /** There is no speech-recognition service to bind, so no session can start. */
        void onNoRecognitionService();

        /** A session ended on an error the user should hear about; {@code messageRes} says which. */
        void onRecognitionError(int messageRes);

        /** The transcription so far of the session in progress. */
        void onPartialResult(String text);

        /** The session ended without a final result: whatever partial text is showing stays as it is. */
        void onDictationAbandoned();
    }

    public VoiceRecognitionManager(Context context, VoiceInputController c1122b) {
        this.mContext = context.getApplicationContext();
        this.mSelection = InstalledVoiceRecognizers.resolve(this.mContext);
        this.mSpeechRecognizer = InstalledVoiceRecognizers.create(this.mContext, this.mSelection);
        this.mListener = new RecognitionListenerImpl();
        this.mSpeechRecognizer.setRecognitionListener(this.mListener);
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
        this.mFinaliseRunnable = new Runnable() {
            @Override // java.lang.Runnable
            public void run() {
                if (VoiceRecognitionManager.this.mMode == Mode.NONE || !VoiceRecognitionManager.this.mStopRequested) {
                    return;
                }
                Logger.debug(TAG, "No final result after the stop; cancelling");
                VoiceRecognitionManager.this.stopListening();
            }
        };
    }

    /** The recogniser's listener, for a test to play the recogniser's side of the conversation. */
    RecognitionListener listenerForTest() {
        return this.mListener;
    }

    public void startDictation() {
        Logger.debug(TAG, "Dictation button pressed");
        refreshRecognizer();
        SettingsValues c0804dM5050c = SettingsManager.getInstance().getSettingsValues();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        // Dictation, not a search query: the recogniser tunes for running prose.
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
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
        // "Stop listening when you pause". On: one utterance, ended by the recogniser's own
        // endpointer, with a hint to allow a longer pause than its default (a hint only: Google's
        // service caps it). Off: continuous — on Android 13+ a segmented session, which delivers
        // each phrase through onSegmentResults and only ends after CONTINUOUS_SILENCE_MILLIS of
        // silence or our stopListening(); on older Android the plain session, chained again by
        // onResults for as long as the user has not stopped (see chain()).
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
     * The user asked to finish — the on-screen mic while listening. Unlike {@link #stopListening},
     * this keeps what was heard: the recogniser is told to stop and finalise, its final result
     * commits through {@code onResults} (or the last segment) as usual, and only a stop it never
     * answers is cancelled, after {@link #STOP_GRACE_MILLIS}. Before this, a tap while listening
     * cancelled outright and whatever was still being transcribed was lost.
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
        // cancel() fires an async onError(ERROR_CLIENT); mark it deliberate so the
        // transient-error hack below doesn't re-arm mMode (which made the mic
        // untappable: every tap "stopped" a phantom session forever).
        this.mCancelRequested = true;
        this.mSpeechRecognizer.stopListening();
        this.mSpeechRecognizer.cancel();
        this.mMode = Mode.NONE;
        // cancel() means no final result will come for whatever was being transcribed.
        this.mCallback.onDictationAbandoned();
        notifyState(STATE_STOPPED);
    }

    /**
     * Continuous mode: whether to start the next session when the current one ends on its own —
     * the user has not stopped, the board is still up, and the last few sessions were not all
     * silent (a forgotten open mic is not kept open for ever).
     */
    private boolean shouldChain() {
        return this.mContinuous && !this.mStopRequested && this.mSpeechRecognizer != null
                && this.mController.isViewShowing() && this.mEmptySessionsInARow < MAX_EMPTY_SESSIONS;
    }

    /** Starts the next session of a continuous dictation with the same request. */
    private void chain() {
        Logger.debug(TAG, "Continuous: next session (silent in a row: " + this.mEmptySessionsInARow + ")");
        startRecognition(Mode.DICTATION, this.mDictationIntent);
    }

    /** A session ended for good: the flags that only mean something while one runs. */
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

    /**
     * Commits the first result of {@code bundle} as dictation, or abandons the partial when there
     * is none. True when something was committed.
     */
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

    /**
     * Rebuilds the recogniser when the "Speech recognizer" setting no longer resolves to the one it
     * was built for: the setting changed, or the chosen app went away (back to the system default).
     * Runs before each dictation, when no session of ours is live, so a change lands on the next
     * tap without restarting the keyboard. A destroyed manager stays destroyed.
     */
    private void refreshRecognizer() {
        if (this.mSpeechRecognizer == null) {
            return;
        }
        final VoiceRecognizerChoice.Selection selection = InstalledVoiceRecognizers.resolve(this.mContext);
        if (selection.equals(this.mSelection)) {
            return;
        }
        Logger.debug(TAG, "Speech recognizer changed: " + this.mSelection + " -> " + selection);
        // destroy() drops the old listener, so nothing the old recogniser still had queued (the
        // answer to an earlier stop included) can reach this session.
        this.mSpeechRecognizer.destroy();
        this.mCancelRequested = false;
        this.mSelection = selection;
        this.mSpeechRecognizer = InstalledVoiceRecognizers.create(this.mContext, selection);
        this.mListener = new RecognitionListenerImpl();
        this.mSpeechRecognizer.setRecognitionListener(this.mListener);
    }

    /** Whether this keyboard itself holds the microphone permission. */
    private boolean hasOwnMicrophonePermission() {
        return this.mContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Whether the chosen app holds the microphone permission, or null when that cannot be checked.
     * Its package is visible to us through the manifest's {@code RecognitionService} query.
     */
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

    private void startRecognition(Mode aVar, Intent intent) {
        if (this.mSpeechRecognizer == null) {
            return;
        }
        this.mMode = aVar;
        this.mReadyForSpeech = false;
        this.mStopRequested = false;
        this.mCommittedThisSession = false;
        this.mHandler.removeCallbacks(this.mFinaliseRunnable);
        this.mStaleClientErrorPossible = this.mCancelRequested;
        this.mCancelRequested = false;
        notifyState(STATE_STARTED);
        this.mSpeechRecognizer.startListening(intent);
        this.mHandler.removeCallbacks(this.mTimeoutRunnable);
        // The "nothing heard for 6 s" cut-off is for one-utterance dictation. Continuous mode
        // waits: the recogniser's own no-speech timeout ends a silent session, and the
        // silent-sessions cap in shouldChain() keeps that from going on for ever.
        if (!this.mContinuous) {
            this.mHandler.postDelayed(this.mTimeoutRunnable, 6000L);
        }
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

    /**
     * The "Show words as you speak" setting. Read from the current {@link SettingsValues} for each
     * partial rather than kept from when the recogniser was built, so a change in settings applies
     * from the next dictation with no restart. The request is the same either way: partial results
     * are still asked for, and only their forwarding to the editor depends on this.
     */
    private static boolean showsPartialResults() {
        SettingsValues settingsValues = SettingsManager.getInstance().getSettingsValues();
        return settingsValues == null || settingsValues.voiceInputShowPartialResults;
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
            final boolean sessionActive = VoiceRecognitionManager.this.mMode != Mode.NONE;
            final boolean noService = VoiceRecognitionAvailability.isNoServiceError(i, sessionActive,
                    VoiceRecognitionManager.this.mReadyForSpeech, VoiceRecognitionManager.this.mStaleClientErrorPossible);
            // The two flags that describe the session that just ended; put back below in the one
            // case where it has not ended (the transient ERROR_CLIENT re-arm).
            final boolean stopRequested = VoiceRecognitionManager.this.mStopRequested;
            final boolean continuous = VoiceRecognitionManager.this.mContinuous;
            VoiceRecognitionManager.this.mMode = Mode.NONE;
            VoiceRecognitionManager.this.mReadyForSpeech = false;
            VoiceRecognitionManager.this.mStopRequested = false;
            VoiceRecognitionManager.this.mContinuous = false;
            VoiceRecognitionManager.this.mHandler.removeCallbacks(VoiceRecognitionManager.this.mFinaliseRunnable);
            // Deliberate stop/cancel: the session is over; skip the transient-error
            // handling entirely (notably the case-5 mMode re-arm).
            if (VoiceRecognitionManager.this.mCancelRequested) {
                VoiceRecognitionManager.this.mCancelRequested = false;
                if (i != 5) {
                    VoiceRecognitionManager.this.notifyState(STATE_STOPPED);
                }
                return;
            }
            if (i == 5 && !sessionActive) {
                // Nothing of ours was running: the answer to a stop with no session behind it (or
                // the platform's second report of a missing service). Re-arming DICTATION here is
                // the phantom session the stopListening() note describes.
                return;
            }
            if (i == 5) {
                // Whatever this ERROR_CLIENT answered, the next one is this session's own.
                VoiceRecognitionManager.this.mStaleClientErrorPossible = false;
            }
            if (noService) {
                // No service to bind (KEY2, 2026-10-06): this used to re-arm DICTATION and never
                // send STOPPED, leaving the board "listening" to nothing.
                VoiceRecognitionManager.this.mCallback.onDictationAbandoned();
                VoiceRecognitionManager.this.notifyState(STATE_STOPPED);
                VoiceRecognitionManager.this.mCallback.onNoRecognitionService();
                return;
            }
            if (i == 5 && VoiceRecognitionManager.this.mController.isViewShowing()) {
                // Transient: the answer to an earlier stop, arriving after this session started.
                // The session is still live, so its partial text stays composing.
                VoiceRecognitionManager.this.mMode = Mode.DICTATION;
                VoiceRecognitionManager.this.mStopRequested = stopRequested;
                VoiceRecognitionManager.this.mContinuous = continuous;
                return;
            }
            VoiceRecognitionManager.this.mCallback.onDictationAbandoned();
            final boolean nothingHeard = i == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || i == SpeechRecognizer.ERROR_NO_MATCH;
            if (nothingHeard && continuous && !stopRequested && sessionActive) {
                // Continuous dictation: a silent session is just a pause. Chain the next one
                // without a word, until the silent-sessions cap says the mic was forgotten.
                VoiceRecognitionManager.this.mContinuous = true;
                VoiceRecognitionManager.this.noteSessionOutcome();
                if (VoiceRecognitionManager.this.shouldChain()) {
                    VoiceRecognitionManager.this.chain();
                    return;
                }
                VoiceRecognitionManager.this.mContinuous = false;
            }
            if (i != 5) {
                VoiceRecognitionManager.this.notifyState(STATE_STOPPED);
            }
            // A stop with nothing said: the user ended it, so "No speech heard" is noise.
            final int message = nothingHeard && stopRequested
                    ? VoiceRecognitionAvailability.NO_MESSAGE : VoiceRecognitionAvailability.messageForError(i);
            if (message != VoiceRecognitionAvailability.NO_MESSAGE) {
                // These used to end the session silently, straight back to "Tap the mic to speak".
                VoiceRecognitionManager.this.mCallback.onRecognitionError(message);
            }
            switch (i) {
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
                        // STOPPED has already gone out above, so the board is idle; with a chosen
                        // app, asking for our own permission again cannot help, so say what is wrong.
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
            if (VoiceRecognitionManager.this.mMode != Mode.DICTATION || VoiceRecognitionManager.this.mCancelRequested) {
                return;
            }
            if (!showsPartialResults()) {
                // "Show words as you speak" is off: nothing reaches the editor until onResults
                // commits the final result, as it did before partials were shown at all.
                return;
            }
            ArrayList<String> partials = bundle == null ? null
                    : bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (partials != null && !partials.isEmpty() && partials.get(0) != null) {
                VoiceRecognitionManager.this.mCallback.onPartialResult(partials.get(0));
            }
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
            VoiceRecognitionManager.this.commitResult(bundle);
            VoiceRecognitionManager.this.mReadyForSpeech = false;
            VoiceRecognitionManager.this.noteSessionOutcome();
            if (VoiceRecognitionManager.this.shouldChain()) {
                // Continuous dictation on a recogniser without segmented sessions (or one that
                // ended the session anyway): straight on to the next one.
                VoiceRecognitionManager.this.chain();
                return;
            }
            VoiceRecognitionManager.this.endSession();
            VoiceRecognitionManager.this.notifyState(STATE_STOPPED);
        }

        @Override // android.speech.RecognitionListener
        public void onSegmentResults(Bundle bundle) {
            // Continuous mode on Android 13+: one phrase of a session that goes on. Commit it and
            // keep listening; nothing about the session changes.
            Logger.debug(VoiceRecognitionManager.TAG, "onSegmentResults");
            if (VoiceRecognitionManager.this.mMode != Mode.DICTATION) {
                return;
            }
            if (VoiceRecognitionManager.this.commitResult(bundle)) {
                VoiceRecognitionManager.this.mEmptySessionsInARow = 0;
            }
        }

        @Override // android.speech.RecognitionListener
        public void onEndOfSegmentedSession() {
            // The segmented session ended: our stop, or CONTINUOUS_SILENCE_MILLIS of silence. A
            // session that heard something and was not stopped is chained like a plain one; a
            // silent one counts toward the cap.
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

        @Override // android.speech.RecognitionListener
        public void onRmsChanged(float f) {
            Logger.debug(VoiceRecognitionManager.TAG, "onRmsChanged:" + f);
            // Drives the Material waveform; no-op under Classic/Modern.
            VoiceRecognitionManager.this.mController.onAudioLevel(f);
        }
    }
}
