package dev.bbkb.ime.core.suggestion

import android.util.Log
import android.view.View
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.core.locale.LocaleUtils
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.BuildConfig

/**
 * Display side of the suggestion pipeline. Hands each result to InputLogic's cache first (the
 * strip must never show words the commit path does not know), then picks the Latin strip, CJK
 * grid, flick overlay or neutral punctuation strip, and keeps the boards and slideboard in step.
 */
class SuggestionStripPresenter(private val ime: BlackBerryIME) {

    /** Async/typed delivery path: an empty result shows the neutral strip. */
    fun showOrNeutral(words: SuggestedWords) {
        val normalized = if (words.isEmpty()) SuggestedWords.EMPTY else words
        if (SuggestedWords.EMPTY == normalized) {
            ime.setNeutralSuggestionStrip()
        } else {
            displayOnStrip(normalized)
        }
    }

    /** Gesture/sync delivery path: an empty result clears the strip. */
    fun display(words: SuggestedWords, isGesture: Boolean) {
        val normalized = if (words.isEmpty()) SuggestedWords.EMPTY else words
        if (SuggestedWords.EMPTY == normalized) {
            clear()
        } else {
            render(normalized, isGesture)
        }
    }

    fun clear() {
        render(SuggestedWords.EMPTY, true)
    }

    /** The neutral strip: suggested punctuation, or nothing when bigram prediction owns the strip. */
    fun showNeutral() {
        if (ime.isUimEnabled() || ime.getKeyboardSwitcher().isInSymbolMode()) {
            val sv = ime.getSettingsManager().getSettingsValues()
            displayOnStrip(if (sv.isBigramPredictionEnabled) SuggestedWords.EMPTY else sv.spacingAndPunctuation.suggestedPunctuations)
        }
    }

    fun updateFlickMetrics() {
        if (ime.isUnifiedInputBoardShowing()) return
        ime.flickSuggestionView?.updateKeyMetrics(ime.getUiUpdateHandler().hasPendingSuggestionUpdate())
    }

    private fun render(words: SuggestedWords, isGesture: Boolean) {
        val sv = ime.getSettingsManager().getSettingsValues()
        ime.getInputLogic().onSuggestionsReceived(words, sv, ime.getUiUpdateHandler())
        if (!ime.hasAuxBarView() || !ime.onEvaluateInputViewShown()) return
        // A swipe's words never take the menu down or close a board; with a board open they go
        // to the hidden strip.
        if (ime.isUnifiedInputBoardShowing()) {
            refreshStripUnderBoard(words, sv)
            return
        }
        val uiCoordinator = ime.getUiCoordinator()
        val subtype = ime.getSubtypeManager().getCurrentSubtype()
        uiCoordinator.updateSuggestionStripVisibility()
        if ((sv.shouldShowPredictionsInCandidateStrip() || sv.shouldShowMoreKeys() || isAutoCorrectOrEmpty(sv, words)) &&
            uiCoordinator.shouldShowSuggestionStrip(sv, ime.isOnScreenKeyboardVisible(), subtype)) {
            putOnStrip(words, false)
        }
        val uibm = ime.getKeyboardSwitcher().getUnifiedInputBoardManager()!!
        if (uibm.isSlideboardShowing()) uibm.showAndRefreshForSlideboard()
    }

    private fun displayOnStrip(words: SuggestedWords) {
        val sv = ime.getSettingsManager().getSettingsValues()
        val inputLogic = ime.getInputLogic()
        if (BuildConfig.DEBUG) {
            val preview = (0 until minOf(words.size(), 5)).joinToString(" ") { "'${words.getWordInfo(it)?.word}'" }
            Log.d("SUGG_COMMIT_DEBUG", "displaySuggestionsOnStrip: setting strip suggestions size=${words.size()}" +
                " willAC=${words.mWillAutoCorrect} preview={$preview } composing='${inputLogic.mComposingTracker.composingText}'")
        }
        inputLogic.onSuggestionsReceived(words, sv, ime.getUiUpdateHandler())
        if (!(ime.hasAuxBarView() || ime.hasFlickSuggestionView()) || !ime.onEvaluateInputViewShown()) return
        if (ime.isUnifiedInputBoardShowing()) {
            refreshStripUnderBoard(words, sv)
            return
        }

        val uiCoordinator = ime.getUiCoordinator()
        val keyboardSwitcher = ime.getKeyboardSwitcher()
        val subtype = ime.getSubtypeManager().getCurrentSubtype()
        val showLatin = uiCoordinator.shouldShowLatinSuggestionStrip(sv, ime.isOnScreenKeyboardVisible(), subtype)
        val showCjk = uiCoordinator.shouldShowCjkSuggestionStrip(sv, ime.isOnScreenKeyboardVisible(), subtype)
        if (words.isEmpty()) ime.cjkSuggestionGridView?.setVisible(false)
        // New words never take the menu down: many non-typing events request words too. Typing
        // puts the menu away itself (InputViewCoordinator.onUserTyped).
        if (sv.isPredictionsEnabled || sv.editorCapabilities.shouldShowChineseSuggestions || sv.shouldShowMoreKeys() || isAutoCorrectOrEmpty(sv, words)) {
            if (showLatin) putOnStrip(words, false)
            if (showCjk) {
                putOnStrip(words, true)
                ime.cjkSuggestionGridView?.setSuggestions(words.getSuggestedWordsForDisplay())
            }
            val uibm = keyboardSwitcher.getUnifiedInputBoardManager()!!
            if (uibm.isSlideboardShowing()) uibm.showAndRefreshForSlideboard()
        }
        updateFlickSuggestions(words, sv)
    }

    /** Shows the words, or keeps them on the hidden strip while the menu covers it. */
    private fun putOnStrip(words: SuggestedWords, isCjk: Boolean) {
        val auxBar = ime.auxBarManager ?: return
        if (ime.getKeyboardSwitcher().getUnifiedInputBoardManager()?.isShowing() == true) {
            auxBar.updateHiddenSuggestionStrip(words, isCjk)
        } else {
            auxBar.showSuggestionStrip(words, isCjk)
        }
    }

    /**
     * A board is open and owns the bar, so only the hidden strip is updated (current when the board
     * closes). With the menu off the strip itself is left alone: its hide calls would close the board.
     */
    private fun refreshStripUnderBoard(words: SuggestedWords, sv: SettingsValues) {
        val uibm = ime.getKeyboardSwitcher().getUnifiedInputBoardManager() ?: return
        if (!uibm.isShowing()) return
        val auxBar = ime.auxBarManager ?: return
        if (!(sv.isPredictionsEnabled || sv.editorCapabilities.shouldShowChineseSuggestions || sv.shouldShowMoreKeys() || isAutoCorrectOrEmpty(sv, words))) return
        val uiCoordinator = ime.getUiCoordinator()
        val subtype = ime.getSubtypeManager().getCurrentSubtype()
        if (uiCoordinator.shouldShowLatinSuggestionStrip(sv, ime.isOnScreenKeyboardVisible(), subtype)) {
            auxBar.updateHiddenSuggestionStrip(words, false)
        }
        if (uiCoordinator.shouldShowCjkSuggestionStrip(sv, ime.isOnScreenKeyboardVisible(), subtype)) {
            auxBar.updateHiddenSuggestionStrip(words, true)
        }
    }

    /**
     * The flick overlay shows the strip's words over the keys when the aux bar is hidden (or the
     * on-screen keyboard is up), gesture typing is off for the locale, predictions are on, and the
     * subtype is neither Chinese nor Japanese.
     */
    private fun updateFlickSuggestions(words: SuggestedWords, sv: SettingsValues) {
        val flick = ime.flickSuggestionView ?: return
        if (!flickShowsWords(sv)) {
            // Not refreshed this time: take down what the overlay showed for the previous result.
            if (flick.visibility == View.VISIBLE) flick.clear()
            return
        }
        // No clear() first: showSuggestions clears only on a change, so an unchanged result does
        // not relayout the keyboard frame.
        flick.showSuggestions(words, ResourceLocaleUtils.isRtlLanguage(ime.getSubtypeManager().getCurrentSubtype()))
    }

    private fun flickShowsWords(sv: SettingsValues): Boolean {
        if (!ime.hasAuxBarView()) return false
        val auxBarHidden = ime.auxBarManager?.getAuxBarView()?.visibility != View.VISIBLE
        if (!(auxBarHidden || DeviceProfile.isOnScreenKeyboardVisible())) return false
        if (sv.isVkbGestureInputEnabledForLocale() || !sv.isPredictionsEnabled) return false
        if (LocaleUtils.isCurrentSubtypeChinese() || LocaleUtils.isCurrentSubtypeJapanese()) return false
        return ime.isOnScreenKeyboardVisible()
    }

    private fun isAutoCorrectOrEmpty(sv: SettingsValues, words: SuggestedWords): Boolean =
        (SuggestedWords.EMPTY == words || words.isAutoCorrection()) || (sv.shouldShowMoreKeys() && words.isEmpty())
}
