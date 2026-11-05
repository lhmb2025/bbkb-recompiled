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
 * Coordinates suggestion and prediction updates for {@link InputLogic}.
 *
 * <p>Owns the logic for requesting, flushing, and delivering word suggestions
 * to the suggestion strip, as well as maintaining the Nuance dictionary context
 * across recorrection sessions.</p>
 */
class SuggestionCoordinator {

    /**
     * Audit DW-6: this path parks the MAIN thread until the worker replies. It was 1000 ms, which
     * is ANR-adjacent — the user sees the keyboard freeze for up to a second right after
     * committing a swiped word, worst right after a language/dictionary switch when the worker is
     * slowest.
     *
     * <p>A normal round-trip is ~10-30 ms, so 250 ms keeps roughly 10x headroom while bounding the
     * stall to something that reads as responsive. On timeout the holder returns null and the
     * strip simply keeps its current contents until the next update, which is a far better
     * failure mode than a visible freeze.
     *
     * <p>The better fix is to make this path async like the typed one, but
     * {@link #updateSuggestionsAsync} is NOT currently equivalent: it lacks the Chinese-locale
     * branch below and additionally manipulates prediction mode before dispatch. Reconciling those
     * is a behaviour change to the CJK gesture path and wants its own verification, so the timeout
     * is bounded here rather than swapped blind.
     */
    private static final long SYNC_SUGGESTION_TIMEOUT_MS = 250L;

    private static final String TAG = "SuggestionCoordinator";

    private final InputLogic mInputLogic;

    /**
     * The latest asynchronous request handed to the worker and the composing-text edit count it
     * was made at. The separator flush waits on it instead of racing it. Main thread only; the
     * holder itself is filled on the worker.
     */
    private AsyncResultHolder<SuggestedWords> mInFlightResult;
    private int mInFlightEditCount = -1;

    SuggestionCoordinator(InputLogic inputLogic) {
        this.mInputLogic = inputLogic;
    }

    /**
     * Synchronously requests suggestions from the suggestion worker and updates the
     * suggestion strip. Blocks the caller until results are ready (up to 1 second).
     * Used only in situations where async delivery is not feasible.
     *
     * @param c0804d current settings values
     * @param i      update reason code forwarded to the worker
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
            // The callback runs on the engine thread, which never reads the tracker (Phase A); the
            // main thread is parked until the reply, so the text read here is the text then.
            final String requestText = mInputLogic.mComposingTracker.getComposingText();
            // keep=true: the main thread is parked on this reply, so it must never be dropped.
            mInputLogic.mSuggestionRequestQueue.sendToWorkerThread(i, true, new SuggestionEngine.SuggestionCallback() {
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
     * Asynchronously requests suggestions or predictions from the suggestion worker.
     * Results are delivered to the UI update handler via {@code postShowSuggestionStrip}.
     *
     * @param c0804d current settings values
     * @param i      update reason code forwarded to the worker
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
            // Audit DW-3: captured HERE, on the main thread before dispatch, so the reply carries
            // the session it was REQUESTED under. Stamping at post time would read as current no
            // matter what, because the worker always posts after any teardown.
            final int requestGeneration = mInputLogic.getSessionGeneration();
            // Same reasoning as the generation: the edit count the request was MADE at. A key
            // typed while the worker runs bumps the tracker's count, so the reply reads as stale.
            final int requestEditCount = mInputLogic.mComposingTracker.getEditCount();
            final AsyncResultHolder<SuggestedWords> inFlight = new AsyncResultHolder<>();
            mInFlightResult = inFlight;
            mInFlightEditCount = requestEditCount;
            // The text the request is made for: the callback runs on the engine thread, which
            // never reads the tracker (Phase A). A reply for older text is stale by its edit count.
            final String requestText = mInputLogic.mComposingTracker.getComposingText();
            mInputLogic.mSuggestionRequestQueue.sendToWorkerThread(i, new SuggestionEngine.SuggestionCallback() {
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
     * Makes sure the auto-correction candidate belongs to the text being composed before a
     * commit. If an asynchronous update is still queued, it is cancelled and run synchronously.
     * If one has already been handed to the worker for the current text, the flush waits for
     * that reply and shows it now. Only when neither exists is a synchronous update made.
     *
     * <p>The in-flight case is the race behind "bb sometimes stays bb" (2026-11-02): once the
     * queued update reached the worker nothing was pending any more, but the last keystroke had
     * cleared the candidate and the new one had not arrived (or a reply for the previous text
     * arrived late and stood in for it). A separator in that window committed the typed word
     * with no correction and no text shortcut. It is widest on the physical keyboard (10 ms
     * update delay) and whenever the engine is slow. Waiting on the reply already being computed
     * matters: a fresh synchronous request would queue behind it on the single worker and
     * take twice as long.
     *
     * @param c0804d        current settings values
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
                // A reply to an earlier request that landed in the queue while we waited is
                // older than what was just shown; delivered after the commit it would put a
                // stale strip back.
                handlerC0650c.cancelPendingSuggestionDelivery();
            }
        } else if (awaitingResult) {
            AsyncResultHolder<SuggestedWords> inFlight = mInFlightResult;
            if (inFlight != null && mInFlightEditCount == tracker.getEditCount()) {
                SuggestedWords reply = inFlight.get(null, SYNC_SUGGESTION_TIMEOUT_MS);
                if (BuildConfig.DEBUG) android.util.Log.d("AC_DEBUG", "flushPendingSuggestions: waited for in-flight reply, arrived=" + (reply != null));
                if (reply != null) {
                    // Show it now rather than when its queued MSG_SHOW_SUGGESTIONS comes round,
                    // which would be after this commit.
                    handlerC0650c.cancelPendingSuggestionDelivery();
                    mInputLogic.mSuggestionStripListener.showSuggestionStrip(reply);
                }
                // On timeout the word commits as typed, as it always did; a second request
                // would only queue behind the slow one.
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
     * Builds a new {@link SuggestedWords} list that includes the typed word as the first entry,
     * preserving other suggestions from the given list.
     */
    SuggestedWords createSuggestionsWithTypedWord(String str, SuggestedWords c0666ac) {
        if (c0666ac.isAutoCorrection()) {
            c0666ac = SuggestedWords.EMPTY;
        }
        return new SuggestedWords(SuggestedWords.buildWithTypedWord(str, c0666ac), false, false, c0666ac.mInputStyle);
    }

    /**
     * Updates the Nuance context with the latest cursor position and word, and commits
     * any changed recorrection to the dictionary.
     *
     * @param str  text context before the cursor
     * @param i    cursor position within the recorrection word
     * @param str2 the recorrection word itself
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
     * Commits a changed recorrection word to the main dictionary, then resets recorrection state.
     *
     * @param str text context passed to the dictionary for the commit
     */
    void commitWordToDictionary(String str) {
        // The re-correction is learning (the engine's word-changed note): not while the session may not learn.
        if (mInputLogic.mComposingTracker.hasRecorrectionChanged()
                && mInputLogic.mIme.getDynamicLearningManager().isLearningAllowed()) {
            mInputLogic.mDictionaryLoader.getMainDictionary().onWordChanged(str, mInputLogic.mComposingTracker.getRecorrectionCursorPosition(), mInputLogic.mComposingTracker.getRecorrectionOriginalWord(), mInputLogic.mComposingTracker.getRecorrectionCurrentWord());
        }
        mInputLogic.mComposingTracker.resetRecorrection();
    }
}
