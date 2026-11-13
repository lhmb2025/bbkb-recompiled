package dev.bbkb.ime.core.textinput;

import android.util.Log;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.ime.UIUpdateHandler;
import dev.bbkb.ime.core.engine.NuanceSDKManager;
import dev.bbkb.ime.core.engine.PrimaryEngineFacts;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.core.suggestion.SuggestionEngine;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.shared.AsyncResultHolder;
import dev.bbkb.ime.core.textinput.composing.ComposingTextTracker;
import dev.bbkb.ime.BuildConfig;

/**
 * Requests, flushes and delivers suggestions for {@link InputLogic}, and keeps the engine's
 * recorrection context.
 */
class SuggestionCoordinator {

    /**
     * The sync path parks the main thread; a round trip is ~10-30 ms. On timeout the strip keeps
     * its contents. Not swapped for {@link #updateSuggestionsAsync}, which lacks the Chinese
     * branch and changes prediction mode.
     */
    private static final long SYNC_SUGGESTION_TIMEOUT_MS = 250L;

    private static final String TAG = "SuggestionCoordinator";

    private final InputLogic mInputLogic;

    /**
     * The latest async request and the edit count it was made at; the separator flush waits on
     * it. Main thread only; the holder is filled on the worker.
     */
    private AsyncResultHolder<SuggestedWords> mInFlightResult;
    private int mInFlightEditCount = -1;

    SuggestionCoordinator(InputLogic inputLogic) {
        this.mInputLogic = inputLogic;
    }

    /**
     * Blocks for the worker's suggestions (up to {@link #SYNC_SUGGESTION_TIMEOUT_MS}).
     *
     * @param c0804d current settings values
     * @param i update reason code forwarded to the worker
     * @return {@code true} if the worker answered in time and its result was shown
     */
    boolean updateSuggestionsSync(SettingsValues c0804d, int i) {
        if (!c0804d.shouldShowLxxButton) {
            if (mInputLogic.mComposingTracker.isComposing()) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Called updateSuggestionsOrPredictions but suggestions were not requested!");
            }
            mInputLogic.mSuggestionStripListener.showSuggestionStrip(SuggestedWords.EMPTY);
        } else if (!InputLogic.needsSuggestionLookups(c0804d) && !NuanceSDKManager.isGesturePending()) {
            // Composing only for learning: nothing consumes a candidate list, so none is built.
            mInputLogic.mSuggestionStripListener.showSuggestionStrip(SuggestedWords.EMPTY);
        } else {
            if (!mInputLogic.mComposingTracker.isComposing() && !c0804d.isBigramPredictionEnabled) {
                mInputLogic.mSuggestionStripListener.setNeutralSuggestionStrip();
                return false;
            }
            final AsyncResultHolder c0883c = new AsyncResultHolder();
            // The main thread is parked until the reply, so no edit can land in between.
            final int requestEditCount = mInputLogic.mComposingTracker.getEditCount();
            // Read here: the callback runs on the engine thread, which never reads the tracker.
            final String requestText = mInputLogic.mComposingTracker.getComposingText();
            // keep=true: the main thread is parked on this reply, so it must never be dropped.
            mInputLogic.mSuggestionRequestQueue.sendToWorkerThread(i, true, new SuggestionEngine.SuggestionCallback() {
                /**
                 * @param c0666ac the suggestions the worker produced
                 */
                @Override
                public void onSuggestionsReady(SuggestedWords c0666ac) {
                    String strM4360m = requestText;
                    if (c0666ac.size() > 1 || strM4360m.length() <= 1) {
                        c0883c.set(stampEditCount(c0666ac, requestEditCount));
                    } else {
                        if (PrimaryEngineFacts.get().isCurrLocaleChinese()) {
                            c0883c.set(stampEditCount(c0666ac, requestEditCount));
                            return;
                        }
                        c0883c.set(stampEditCount(createSuggestionsWithTypedWord(strM4360m, mInputLogic.mCurrentSuggestions), requestEditCount));
                    }
                }
            });
            SuggestedWords c0666ac = (SuggestedWords) c0883c.get(null, SYNC_SUGGESTION_TIMEOUT_MS);
            if (c0666ac != null) {
                mInputLogic.mSuggestionStripListener.showSuggestionStrip(c0666ac);
                return true;
            }
        }
        return false;
    }

    /**
     * Results arrive through {@code postShowSuggestionStrip}.
     * @param c0804d current settings values
     * @param i update reason code forwarded to the worker
     */
    void updateSuggestionsAsync(SettingsValues c0804d, int i) {
        if (BuildConfig.DEBUG) android.util.Log.d("SUGG", "updateSuggestionsAsync: shouldShow=" + c0804d.shouldShowLxxButton + " composing=" + mInputLogic.mComposingTracker.isComposing() + " bigram=" + c0804d.isBigramPredictionEnabled);
        if (!c0804d.shouldShowLxxButton) {
            if (mInputLogic.mComposingTracker.isComposing()) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Called updateSuggestionsOrPredictions but suggestions were not requested!");
            }
            mInputLogic.mSuggestionStripListener.showSuggestionStrip(SuggestedWords.EMPTY);
        } else if (!InputLogic.needsSuggestionLookups(c0804d) && !NuanceSDKManager.isGesturePending()) {
            // Composing only for learning: nothing consumes a candidate list, so none is built.
            mInputLogic.mSuggestionStripListener.showSuggestionStrip(SuggestedWords.EMPTY);
        } else if (!mInputLogic.mComposingTracker.isComposing() && !c0804d.isBigramPredictionEnabled) {
            if (BuildConfig.DEBUG) android.util.Log.d("SUGG", "updateSuggestionsAsync: not composing + bigram off -> setNeutralSuggestionStrip");
            mInputLogic.mSuggestionStripListener.setNeutralSuggestionStrip();
        } else {
            boolean composingNow = mInputLogic.mComposingTracker.isComposing();
            if (BuildConfig.DEBUG) android.util.Log.d("SUGG", "updateSuggestionsAsync: dispatching composing=" + composingNow + " composingText='" + mInputLogic.mComposingTracker.getComposingText() + "'");
            if (!composingNow) {
                mInputLogic.mComposingTracker.enterPredictionMode();
            } else {
                mInputLogic.mComposingTracker.resetPredictionState();
            }
            if (BuildConfig.DEBUG) android.util.Log.d("SUGG", "updateSuggestionsAsync: predictionMode=" + mInputLogic.mComposingTracker.isPredictionMode());
            // Captured before dispatch: stamping at post time would always read as current.
            final int requestGeneration = mInputLogic.getSessionGeneration();
            // A key typed while the worker runs bumps the count, so the reply reads as stale.
            final int requestEditCount = mInputLogic.mComposingTracker.getEditCount();
            final AsyncResultHolder<SuggestedWords> inFlight = new AsyncResultHolder<>();
            mInFlightResult = inFlight;
            mInFlightEditCount = requestEditCount;
            // Read here: the callback runs on the engine thread, which never reads the tracker.
            final String requestText = mInputLogic.mComposingTracker.getComposingText();
            mInputLogic.mSuggestionRequestQueue.sendToWorkerThread(i, new SuggestionEngine.SuggestionCallback() {
                /**
                 * @param c0666ac the suggestions the worker produced
                 */
                @Override
                public void onSuggestionsReady(SuggestedWords c0666ac) {
                    if (BuildConfig.DEBUG) android.util.Log.d("SUGG", "updateSuggestionsAsync: onSuggestionsReady size=" + c0666ac.size() + " thread=" + Thread.currentThread().getName());
                    String strM4360m = requestText;
                    SuggestedWords reply = c0666ac.size() > 1 || strM4360m.length() <= 1
                            ? c0666ac
                            : createSuggestionsWithTypedWord(strM4360m, mInputLogic.mCurrentSuggestions);
                    stampEditCount(reply, requestEditCount);
                    inFlight.set(reply);
                    mInputLogic.mIme.uiUpdateHandler.postShowSuggestionStrip(reply, requestGeneration);
                }
            });
        }
    }

    /**
     * Makes the auto-correction match the composing text before a commit: a queued update runs
     * synchronously; one already in flight for this text is waited on (a new request would queue
     * behind it); otherwise a sync update is made.
     * @param c0804d current settings values
     * @param handlerC0650c UI update handler used to cancel pending async requests
     */
    void flushPendingSuggestions(SettingsValues c0804d, UIUpdateHandler handlerC0650c) {
        boolean hasPendingUpdate = handlerC0650c.hasPendingSuggestionUpdate();
        boolean hasPendingJapanese = handlerC0650c.hasPendingJapaneseSuggestionUpdate();
        ComposingTextTracker tracker = mInputLogic.mComposingTracker;
        boolean awaitingResult = tracker.isComposing() && !tracker.isGestureInput()
                && !tracker.hasAutoCorrectionForComposingText();
        if (BuildConfig.DEBUG) {
        android.util.Log.d("AC_DEBUG", "flushPendingSuggestions: hasPendingUpdate=" + hasPendingUpdate
            + " hasPendingJapanese=" + hasPendingJapanese
            + " awaitingResult=" + awaitingResult
            + " composing='" + tracker.getComposingText() + "'");
        }
        if (hasPendingUpdate || hasPendingJapanese) {
            handlerC0650c.cancelPendingSuggestionUpdates();
            if (BuildConfig.DEBUG) android.util.Log.d("AC_DEBUG", "flushPendingSuggestions: running sync update");
            if (updateSuggestionsSync(c0804d, 1)) {
                // An older reply queued meanwhile would put a stale strip back after the commit.
                handlerC0650c.cancelPendingSuggestionDelivery();
            }
        } else if (awaitingResult) {
            AsyncResultHolder<SuggestedWords> inFlight = mInFlightResult;
            if (inFlight != null && mInFlightEditCount == tracker.getEditCount()) {
                SuggestedWords reply = inFlight.get(null, SYNC_SUGGESTION_TIMEOUT_MS);
                if (BuildConfig.DEBUG) android.util.Log.d("AC_DEBUG", "flushPendingSuggestions: waited for in-flight reply, arrived=" + (reply != null));
                if (reply != null) {
                    // Show it now; its queued delivery would land after this commit.
                    handlerC0650c.cancelPendingSuggestionDelivery();
                    mInputLogic.mSuggestionStripListener.showSuggestionStrip(reply);
                }
                // On timeout the word commits as typed.
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("AC_DEBUG", "flushPendingSuggestions: nothing in flight for this text, running sync update");
                if (updateSuggestionsSync(c0804d, 1)) {
                    handlerC0650c.cancelPendingSuggestionDelivery();
                }
            }
        } else {
            if (BuildConfig.DEBUG) {
            android.util.Log.d("AC_DEBUG", "flushPendingSuggestions: NO pending update, using cached acWord='"
                + mInputLogic.mComposingTracker.getAutoCorrection() + "' score=" + mInputLogic.mComposingTracker.getAutoCorrectionScore());
            }
        }
    }

    /** Records the request's edit count on {@code words}, never on the shared EMPTY instance. */
    private static SuggestedWords stampEditCount(SuggestedWords words, int editCount) {
        if (words != SuggestedWords.EMPTY) {
            words.mRequestEditCount = editCount;
        }
        return words;
    }

    /**
     * The typed word first, then the given list's suggestions (dropped if it is an auto-correction).
     * @param str the typed word to put first
     * @param c0666ac the suggestions to append after the typed word
     */
    SuggestedWords createSuggestionsWithTypedWord(String str, SuggestedWords c0666ac) {
        if (c0666ac.isAutoCorrection()) {
            c0666ac = SuggestedWords.EMPTY;
        }
        return new SuggestedWords(SuggestedWords.buildWithTypedWord(str, c0666ac), false, false, c0666ac.mInputStyle);
    }

    /**
     * @param str  text before the cursor
     * @param i    cursor position within the recorrection word
     * @param str2 the recorrection word
     */
    void updateNuanceContext(String str, int i, String str2) {
        if (mInputLogic.mComposingTracker.getRecorrectionCurrentWord() != null && mInputLogic.mComposingTracker.getRecorrectionCurrentWord().isEmpty()) {
            mInputLogic.mComposingTracker.resetRecorrection();
        } else {
            if (mInputLogic.mComposingTracker.isGestureInput()) {
                if (mInputLogic.mComposingTracker.getRecorrectionCursorPosition() != i) {
                    commitWordToDictionary(str);
                }
                mInputLogic.mComposingTracker.setRecorrectionInfo(i, str2);
                return;
            }
            commitWordToDictionary(str);
        }
    }

    /**
     * Reports a changed recorrection to the dictionary, then resets recorrection state.
     * @param str text context passed to the dictionary for the commit
     */
    void commitWordToDictionary(String str) {
        // This is learning, so it is skipped while the session may not learn.
        if (mInputLogic.mComposingTracker.hasRecorrectionChanged()
                && mInputLogic.mIme.getDynamicLearningManager().isLearningAllowed()) {
            mInputLogic.mDictionaryLoader.getMainDictionary().onWordChanged(str, mInputLogic.mComposingTracker.getRecorrectionCursorPosition(), mInputLogic.mComposingTracker.getRecorrectionOriginalWord(), mInputLogic.mComposingTracker.getRecorrectionCurrentWord());
        }
        mInputLogic.mComposingTracker.resetRecorrection();
    }
}
