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
 * Puts a [SuggestedWords] result on screen: hands it to InputLogic's cache first (that ordering
 * is load-bearing: the strip must never show words the commit path does not know about), then
 * decides between the Latin strip, the CJK grid, the flick overlay and the neutral punctuation
 * strip, and keeps the input boards / slideboard consistent with what was shown.
 *
 * [SuggestionUpdater] is the request side of the same pipeline; this is the display side.
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
        val uiCoordinator = ime.getUiCoordinator()
        val subtype = ime.getSubtypeManager().getCurrentSubtype()
        uiCoordinator.updateSuggestionStripVisibility()
        if (!ime.shouldShowUim()) uiCoordinator.hideUnifiedInputBoard()
        if ((sv.shouldShowPredictionsInCandidateStrip() || sv.shouldShowMoreKeys() || isAutoCorrectOrEmpty(sv, words)) &&
            uiCoordinator.shouldShowSuggestionStrip(sv, ime.isOnScreenKeyboardVisible(), subtype)) {
            ime.auxBarManager?.showSuggestionStrip(words, false)
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
        if (ime.isUnifiedInputBoardShowing()) return

        val uiCoordinator = ime.getUiCoordinator()
        val keyboardSwitcher = ime.getKeyboardSwitcher()
        val subtype = ime.getSubtypeManager().getCurrentSubtype()
        val showLatin = uiCoordinator.shouldShowLatinSuggestionStrip(sv, ime.isOnScreenKeyboardVisible(), subtype)
        ime.flickSuggestionView?.let { if (it.visibility == View.VISIBLE) it.clear() }
        val showCjk = uiCoordinator.shouldShowCjkSuggestionStrip(sv, ime.isOnScreenKeyboardVisible(), subtype)
        if (words.isEmpty()) ime.cjkSuggestionGridView?.setVisible(false)
        if (!ime.shouldShowUim() && !keyboardSwitcher.isInSymbolMode() && ime.fccController?.consumeClosingFlag() == false) {
            uiCoordinator.hideUnifiedInputBoard()
        }
        if (sv.isPredictionsEnabled || sv.editorCapabilities.shouldShowChineseSuggestions || sv.shouldShowMoreKeys() || isAutoCorrectOrEmpty(sv, words)) {
            val auxBar = ime.auxBarManager
            if (showLatin && auxBar != null) {
                auxBar.showSuggestionStrip(words, false)
                uiCoordinator.hideUnifiedInputBoard()
            }
            if (showCjk && auxBar != null) {
                auxBar.showSuggestionStrip(words, true)
                ime.cjkSuggestionGridView!!.setSuggestions(words.getSuggestedWordsForDisplay())
                uiCoordinator.hideUnifiedInputBoard()
            }
            val uibm = keyboardSwitcher.getUnifiedInputBoardManager()!!
            if (uibm.isSlideboardShowing()) uibm.showAndRefreshForSlideboard()
        }
        updateFlickSuggestions(words, sv)
    }

    /**
     * The flick overlay shows the strip's words over the keys when the aux bar is hidden (or the
     * on-screen keyboard is up), gesture typing is off for the locale, predictions are on, and the
     * subtype is neither Chinese nor Japanese.
     */
    private fun updateFlickSuggestions(words: SuggestedWords, sv: SettingsValues) {
        val flick = ime.flickSuggestionView ?: return
        if (!ime.hasAuxBarView()) return
        val auxBarHidden = ime.auxBarManager?.getAuxBarView()?.visibility != View.VISIBLE
        if (!(auxBarHidden || DeviceProfile.isOnScreenKeyboardVisible())) return
        if (sv.isVkbGestureInputEnabledForLocale() || !sv.isPredictionsEnabled) return
        if (LocaleUtils.isCurrentSubtypeChinese() || LocaleUtils.isCurrentSubtypeJapanese()) return
        if (!ime.isOnScreenKeyboardVisible()) return
        flick.setSuggestions(words, ResourceLocaleUtils.isRtlLanguage(ime.getSubtypeManager().getCurrentSubtype()))
    }

    private fun isAutoCorrectOrEmpty(sv: SettingsValues, words: SuggestedWords): Boolean =
        (SuggestedWords.EMPTY == words || words.isAutoCorrection()) || (sv.shouldShowMoreKeys() && words.isEmpty())
}
