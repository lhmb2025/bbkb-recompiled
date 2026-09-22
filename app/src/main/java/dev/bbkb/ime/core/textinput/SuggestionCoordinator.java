package dev.bbkb.ime.core.textinput;

import android.util.Log;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.ime.UIUpdateHandler;
import dev.bbkb.ime.core.engine.NuanceSDKManager;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.core.suggestion.SuggestionEngine;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.shared.AsyncResultHolder;
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
     */
    void updateSuggestionsSync(SettingsValues c0804d, int i) {
        if (!c0804d.shouldShowLxxButton) {
            if (mInputLogic.mComposingTracker.isComposing()) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Called updateSuggestionsOrPredictions but suggestions were not requested!");
            }
            mInputLogic.mSuggestionStripListener.showSuggestionStrip(SuggestedWords.EMPTY);
        } else {
            if (!mInputLogic.mComposingTracker.isComposing() && !c0804d.isBigramPredictionEnabled) {
                mInputLogic.mSuggestionStripListener.setNeutralSuggestionStrip();
                return;
            }
            final AsyncResultHolder c0883c = new AsyncResultHolder();
            mInputLogic.mSuggestionRequestQueue.sendToWorkerThread(i, new SuggestionEngine.SuggestionCallback() {
                @Override
                public void onSuggestionsReady(SuggestedWords c0666ac) {
                    String strM4360m = mInputLogic.mComposingTracker.getComposingText();
                    if (c0666ac.size() > 1 || strM4360m.length() <= 1) {
                        c0883c.set(c0666ac);
                    } else {
                        if (NuanceSDKManager.getInstance().isCurrLocaleChinese()) {
                            c0883c.set(c0666ac);
                            return;
                        }
                        c0883c.set(createSuggestionsWithTypedWord(strM4360m, mInputLogic.mCurrentSuggestions));
                    }
                }
            });
            SuggestedWords c0666ac = (SuggestedWords) c0883c.get(null, SYNC_SUGGESTION_TIMEOUT_MS);
            if (c0666ac != null) {
                mInputLogic.mSuggestionStripListener.showSuggestionStrip(c0666ac);
            }
        }
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
            mInputLogic.mSuggestionRequestQueue.sendToWorkerThread(i, new SuggestionEngine.SuggestionCallback() {
                @Override
                public void onSuggestionsReady(SuggestedWords c0666ac) {
                    if (BuildConfig.DEBUG) android.util.Log.d("SUGG", "updateSuggestionsAsync: onSuggestionsReady size=" + c0666ac.size() + " thread=" + Thread.currentThread().getName());
                    String strM4360m = mInputLogic.mComposingTracker.getComposingText();
                    if (c0666ac.size() > 1 || strM4360m.length() <= 1) {
                        mInputLogic.mIme.uiUpdateHandler.postShowSuggestionStrip(c0666ac, requestGeneration);
                        return;
                    }
                    SuggestedWords processedSuggestions = createSuggestionsWithTypedWord(strM4360m, mInputLogic.mCurrentSuggestions);
                    mInputLogic.mIme.uiUpdateHandler.postShowSuggestionStrip(processedSuggestions, requestGeneration);
                }
            });
        }
    }

    /**
     * If there is a pending asynchronous suggestion update, cancels it and runs a synchronous
     * update instead. Ensures suggestion state is consistent before a commit.
     *
     * @param c0804d        current settings values
     * @param handlerC0650c UI update handler used to cancel pending async requests
     */
    void flushPendingSuggestions(SettingsValues c0804d, UIUpdateHandler handlerC0650c) {
        boolean hasPendingUpdate = handlerC0650c.hasPendingSuggestionUpdate();
        boolean hasPendingJapanese = handlerC0650c.hasPendingJapaneseSuggestionUpdate();
        if (BuildConfig.DEBUG) {
        android.util.Log.d("AC_DEBUG", "flushPendingSuggestions: hasPendingUpdate=" + hasPendingUpdate
            + " hasPendingJapanese=" + hasPendingJapanese
            + " composing='" + mInputLogic.mComposingTracker.getComposingText() + "'");
        }
        if (hasPendingUpdate || hasPendingJapanese) {
            handlerC0650c.cancelPendingSuggestionUpdates();
            if (BuildConfig.DEBUG) android.util.Log.d("AC_DEBUG", "flushPendingSuggestions: running sync update");
            updateSuggestionsSync(c0804d, 1);
        } else {
            if (BuildConfig.DEBUG) {
            android.util.Log.d("AC_DEBUG", "flushPendingSuggestions: NO pending update, using cached acWord='"
                + mInputLogic.mComposingTracker.getAutoCorrection() + "' score=" + mInputLogic.mComposingTracker.getAutoCorrectionScore());
            }
        }
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
        if (mInputLogic.mComposingTracker.hasRecorrectionChanged()) {
            mInputLogic.mDictionaryLoader.getMainDictionary().onWordChanged(str, mInputLogic.mComposingTracker.getRecorrectionCursorPosition(), mInputLogic.mComposingTracker.getRecorrectionOriginalWord(), mInputLogic.mComposingTracker.getRecorrectionCurrentWord());
        }
        mInputLogic.mComposingTracker.resetRecorrection();
    }
}
