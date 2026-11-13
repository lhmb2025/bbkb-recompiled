package dev.bbkb.ime.core.ime
import android.content.res.Resources
import android.os.Message
import android.util.Log
import android.view.inputmethod.EditorInfo
import androidx.core.os.HandlerCompat
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.keyevent.InputSource
import dev.bbkb.ime.core.suggestion.SuggestedWords
import dev.bbkb.ime.core.shared.WeakOwnerHandler
import dev.bbkb.ime.core.shared.InputPathDebug
import dev.bbkb.ime.core.shared.Logger
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import dev.bbkb.ime.keyboard.internal.KeyboardId
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.R
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.engine.NuanceSDKManager

/**
 * The IME's main-thread message queue: delayed suggestion/shift updates, dictionary-load
 * bookkeeping, and the orientation-change deferral of start/finish-input events. Holds only a
 * weak reference to the service (see [WeakOwnerHandler]).
 */
class UIUpdateHandler(blackBerryIME: BlackBerryIME) : WeakOwnerHandler<BlackBerryIME>(blackBerryIME) {

    companion object {
        private const val MSG_UPDATE_SHIFT_STATE = 0
        private const val MSG_ORIENTATION_DELAY = 1
        private const val MSG_UPDATE_SUGGESTIONS = 2
        private const val MSG_SHOW_SUGGESTIONS = 3
        private const val MSG_UPDATE_SHIFT_MODE = 4
        private const val MSG_LOAD_ADDITIONAL_LOCALES = 5
        private const val MSG_BATCH_INPUT_SUGGESTIONS = 6
        private const val MSG_SWITCH_KEYBOARD = 7
        private const val MSG_RELOAD_SETTINGS = 8
        private const val MSG_DICTIONARY_LOAD_TIMEOUT = 9
        private const val MSG_MULTITAP_TIMEOUT = 10
        private const val MSG_DICTIONARY_LOADED = 11
        private const val MSG_ADDITIONAL_LOCALES_READY = 12
        private const val MSG_UPDATE_GESTURE_SUGGESTIONS = 14
        private const val MSG_UPDATE_JAPANESE_SUGGESTIONS = 15
        private const val MSG_COMMIT_TEXT = 17
        private const val MSG_SET_COMPOSING_TEXT = 18
        private const val MSG_DEFERRED_CLEANUP_SAFETY = 99

        /** How long start/finish-input events stay deferred after an orientation change. */
        private const val ORIENTATION_DELAY_MS = 800L
    }

    private var suggestionUpdateDelay: Int = 0
    private var shiftStateUpdateDelay: Int = 0
    private var gestureInputSuggestionDelay: Int = 0
    private var multitapDuration: Long = 0L
    private var lastPackageName: String? = null
    private var needsOrientationChange: Boolean = false
    private var shouldDelayInputStart: Boolean = false
    private var pendingStartInput: Boolean = false
    private var pendingFinishInputView: Boolean = false
    private var pendingFinishInput: Boolean = false
    private var lastEditorInfo: EditorInfo? = null

    /**
     * Non-zero while a reply is shown. Showing can re-show the input menu bar, which cancels pending
     * updates; those are for newer text, so [cancelPendingSuggestionUpdates] is a no-op meanwhile.
     */
    private var suggestionDeliveryDepth: Int = 0

    fun initializeDelays() {
        val blackBerryIMEV = getOwner() ?: return
        val resources = blackBerryIMEV.getResources()
        if (DeviceProfile.current().isPkbDevice()) {
            suggestionUpdateDelay = resources.getInteger(R.integer.config_delay_in_msec_to_update_suggestions_pkb)
            Logger.debug("UIUpdateHandler", "PKB device detected - using fast suggestion delay: ${suggestionUpdateDelay}ms")
        } else {
            suggestionUpdateDelay = resources.getInteger(R.integer.config_delay_in_msec_to_update_suggestions)
        }
        shiftStateUpdateDelay = resources.getInteger(R.integer.config_delay_in_msec_to_update_shift_state)
        gestureInputSuggestionDelay = resources.getInteger(R.integer.config_delay_in_msec_to_update_suggestions_after_gesture_input)
        multitapDuration = resources.getInteger(R.integer.config_multitap_duration).toLong()
    }

    @Throws(Resources.NotFoundException::class)
    override fun handleMessage(message: Message) {
        val blackBerryIMEV = getOwner() ?: return
        val c0979i = blackBerryIMEV.getKeyboardSwitcher()
        val i = message.what
        if (i != MSG_UPDATE_SHIFT_STATE) {
            when (i) {
                MSG_UPDATE_SUGGESTIONS -> {
                    cancelPendingSuggestionUpdates()
                    blackBerryIMEV.getInputLogic().updateSuggestionsAsync(blackBerryIMEV.getSettingsManager().getSettingsValues(), message.arg1)
                    return
                }
                MSG_SHOW_SUGGESTIONS -> {
                    val words = message.obj as SuggestedWords
                    if (BuildConfig.DEBUG) Log.d("SUGG", "MSG_SHOW_SUGGESTIONS: arg1=${message.arg1} size=${words.size()} isEmpty=${words.isEmpty()}")
                    // Drop replies from an earlier session here, not by gating writes on isComposing():
                    // mCurrentSuggestions is also the gesture-commit channel, and a gesture is not composing.
                    val currentGeneration = blackBerryIMEV.getInputLogic().getSessionGeneration()
                    if (message.arg2 != currentGeneration) {
                        if (BuildConfig.DEBUG) Log.d("SUGG",
                            "MSG_SHOW_SUGGESTIONS: DROPPING — dispatched in session ${message.arg2}," +
                                " now $currentGeneration (DW-3)")
                        return
                    }
                    deliveringSuggestions {
                        if (message.arg1 != 0) {
                            blackBerryIMEV.displaySuggestions(words, false)
                        } else {
                            blackBerryIMEV.showSuggestionStrip(words)
                        }
                    }
                    return
                }
                MSG_UPDATE_SHIFT_MODE -> {
                    blackBerryIMEV.getInputLogic().performRecorrection(blackBerryIMEV.getSettingsManager().getSettingsValues(), message.arg1 == 1, c0979i.getKeyboardElementId())
                    return
                }
                MSG_LOAD_ADDITIONAL_LOCALES -> {
                    postDictionaryLoadTimeout()
                    blackBerryIMEV.reloadDictionaryForSubtype()
                    return
                }
                MSG_BATCH_INPUT_SUGGESTIONS -> {
                    val source = InputSource.fromId(message.arg1)
                    val words = message.obj as SuggestedWords
                    // Only the display is a delivery; the commit below may cancel and re-request.
                    deliveringSuggestions { blackBerryIMEV.displaySuggestions(words, true) }
                    val wordList: String = if (InputPathDebug.on()) {
                        val sb = StringBuilder()
                        for (i in 0 until words.size()) {
                            sb.append("[").append(i).append("]='").append(words.getWordInfo(i)?.word).append("' ")
                        }
                        sb.toString()
                    } else {
                        ""
                    }
                    // Consume the pending gesture on every result, empty too, or later requests skip
                    // setContextBuffer. False means its session was torn down: display only.
                    // An empty result has no sequence (-1 consumes whatever is pending).
                    val gestureSeq = if (words.size() > 0) words.getWordInfo(0)?.nuanceWordInfo?.gestureSeq ?: -1 else -1
                    val gestureStillPending = NuanceSDKManager.consumeGestureSeq(gestureSeq)
                    if (words.size() > 0) {
                        if (!gestureStillPending) {
                            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "MSG_BATCH_INPUT_SUGGESTIONS: source=$source words={$wordList} — DROPPING commit, gesture no longer pending (session ended)")
                        } else {
                            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "MSG_BATCH_INPUT_SUGGESTIONS: source=$source words={$wordList} — clearing gesturePending and committing top word via commitGestureSuggestion")
                            blackBerryIMEV.getInputLogic().commitGestureSuggestion(blackBerryIMEV.getSettingsManager().getSettingsValues(), words, source)
                        }
                    } else {
                        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "MSG_BATCH_INPUT_SUGGESTIONS: source=$source words=EMPTY — skipping commit")
                    }
                    return
                }
                MSG_SWITCH_KEYBOARD -> {
                    if (blackBerryIMEV.getInputLogic().handleSelectAll(message.arg1 == 1, message.arg2, this)) {
                        c0979i.startInput(blackBerryIMEV.getCurrentInputEditorInfo(), blackBerryIMEV.getCurrentInputType(), blackBerryIMEV.getCurrentImeOptions())
                    }
                    return
                }
                MSG_RELOAD_SETTINGS -> {
                    blackBerryIMEV.updateLanguagePacksForCurrentSubtype()
                    postDictionaryLoadTimeout()
                    blackBerryIMEV.reinitDictionary()
                    return
                }
                MSG_DICTIONARY_LOAD_TIMEOUT -> {
                    if (BuildConfig.DEBUG) Log.i(BlackBerryIME.LOG_TAG, "Timeout waiting for dictionary load")
                    return
                }
                MSG_MULTITAP_TIMEOUT -> {
                    blackBerryIMEV.multitapEventHandler.commitMultitap()
                    blackBerryIMEV.softwareMultitapHandler.commitMultitap()
                    return
                }
                MSG_DICTIONARY_LOADED -> {
                    if (BuildConfig.DEBUG) Log.i(BlackBerryIME.LOG_TAG, "Main dictionary finished loading")
                    blackBerryIMEV.reloadAdditionalLocales()
                    return
                }
                MSG_ADDITIONAL_LOCALES_READY -> {
                    blackBerryIMEV.updateLanguagePacksForCurrentSubtype()
                    if (BuildConfig.DEBUG) Log.i(BlackBerryIME.LOG_TAG, "Additional locales ready for load")
                    if (hasDictionaryLoadTimeout()) {
                        return
                    }
                    blackBerryIMEV.reloadAdditionalLocales()
                    return
                }
                MSG_UPDATE_GESTURE_SUGGESTIONS -> {
                    cancelPendingSuggestionUpdates()
                    if (!blackBerryIMEV.getSettingsManager().getSettingsValues().isPredictionsEnabled) {
                        blackBerryIMEV.clearSuggestions()
                        return
                    }
                    blackBerryIMEV.getInputLogic().updateSuggestionsSync(blackBerryIMEV.getSettingsManager().getSettingsValues(), message.arg1)
                    return
                }
                MSG_UPDATE_JAPANESE_SUGGESTIONS -> {
                    cancelPendingSuggestionUpdates()
                    blackBerryIMEV.getInputLogic().updateSuggestionsAsync(blackBerryIMEV.getSettingsManager().getSettingsValues(), message.arg1)
                    return
                }
                MSG_COMMIT_TEXT -> {
                    blackBerryIMEV.setInsertAsString(true)
                    blackBerryIMEV.onTextInput(message.obj as String, 0L)
                    return
                }
                MSG_SET_COMPOSING_TEXT -> {
                    blackBerryIMEV.getInputLogic().setVoiceComposingText(message.obj as String)
                    return
                }
                MSG_DEFERRED_CLEANUP_SAFETY -> {
                    executeDeferredCleanupSafety()
                    return
                }
            }
            if (BuildConfig.DEBUG) Log.d(BlackBerryIME.LOG_TAG, "The message is unhandled")
            return
        }
        blackBerryIMEV.resetKeyboardState()
    }

    /** @param str text to commit */
    fun postCommitText(str: String) {
        sendMessage(obtainMessage(MSG_COMMIT_TEXT, str))
    }

    /**
     * A dictation partial shown as composing text; queued with [postCommitText] so the final commit
     * replaces it. An older queued partial is dropped.
     * @param str the partial result text
     */
    fun postSetComposingText(str: String) {
        removeMessages(MSG_SET_COMPOSING_TEXT)
        sendMessage(obtainMessage(MSG_SET_COMPOSING_TEXT, str))
    }

    /**
     * Dictation ended with no final result: keep the shown partial as text. Runs now, not queued: a
     * hardware key closing the voice board in the same turn must not find the partial still composing.
     */
    fun finishVoiceComposingText() {
        removeMessages(MSG_SET_COMPOSING_TEXT)
        getOwner()?.getInputLogic()?.finishVoiceComposingText()
    }

    /** Post [action] under [token], so `removeCallbacksAndMessages(token)` cancels it; bypasses [handleMessage]. */
    fun postTokenized(token: Any, delayMillis: Long, action: Runnable) {
        HandlerCompat.postDelayed(this, action, token, delayMillis)
    }

    /** @param i update reason code forwarded to the worker */
    fun postUpdateSuggestions(i: Int) {
        sendMessageDelayed(obtainMessage(MSG_UPDATE_SUGGESTIONS, i, 0), suggestionUpdateDelay.toLong())
    }

    /** @param i update reason code forwarded to the worker */
    fun postUpdateJapaneseSuggestions(i: Int) {
        sendMessageDelayed(obtainMessage(MSG_UPDATE_JAPANESE_SUGGESTIONS, i, 0), suggestionUpdateDelay.toLong())
    }

    /** @param i update reason code forwarded to the worker */
    fun postUpdateGestureSuggestions(i: Int) {
        sendMessageDelayed(obtainMessage(MSG_UPDATE_GESTURE_SUGGESTIONS, i, 0), gestureInputSuggestionDelay.toLong())
    }

    fun postLoadAdditionalLocales() {
        sendMessage(obtainMessage(MSG_LOAD_ADDITIONAL_LOCALES))
    }

    /**
     * Posts a recorrection pass (MSG_UPDATE_SHIFT_MODE) when the suggestion strip is active.
     * @param z include the word under the cursor as the first suggestion
     * @param z2 true to delay by the suggestion-update delay, false to post now
     */
    fun postUpdateShiftState(z: Boolean, z2: Boolean) {
        val blackBerryIMEV = getOwner()
        if (blackBerryIMEV != null && blackBerryIMEV.isSuggestionStripActive()) {
            removeMessages(MSG_UPDATE_SHIFT_MODE)
            if (z2) {
                sendMessageDelayed(obtainMessage(MSG_UPDATE_SHIFT_MODE, if (z) 1 else 0, 0), suggestionUpdateDelay.toLong())
            } else {
                sendMessage(obtainMessage(MSG_UPDATE_SHIFT_MODE, if (z) 1 else 0, 0))
            }
        }
    }

    /**
     * @param z true to update shift state after the connection reset
     * @param i retries left
     */
    fun postUpdateSwitchKeyboard(z: Boolean, i: Int) {
        removeMessages(MSG_SWITCH_KEYBOARD)
        sendMessage(obtainMessage(MSG_SWITCH_KEYBOARD, if (z) 1 else 0, i, null))
    }

    fun postReloadSettings() {
        sendMessage(obtainMessage(MSG_RELOAD_SETTINGS))
    }

    fun postDictionaryLoadTimeout() {
        sendMessageDelayed(obtainMessage(MSG_DICTIONARY_LOAD_TIMEOUT), 2000L)
    }

    fun removeDictionaryLoadTimeout() {
        removeMessages(MSG_DICTIONARY_LOAD_TIMEOUT)
    }

    fun hasDictionaryLoadTimeout(): Boolean {
        return hasMessages(MSG_DICTIONARY_LOAD_TIMEOUT)
    }

    fun postMultitapTimeout() {
        removeMessages(MSG_MULTITAP_TIMEOUT)
        sendMessageDelayed(obtainMessage(MSG_MULTITAP_TIMEOUT), multitapDuration)
    }

    fun postDictionaryLoaded() {
        sendMessage(obtainMessage(MSG_DICTIONARY_LOADED))
    }

    fun postAdditionalLocalesReady() {
        sendMessageDelayed(obtainMessage(MSG_ADDITIONAL_LOCALES_READY), 2000L)
    }

    fun removeAdditionalLocalesReady() {
        removeMessages(MSG_ADDITIONAL_LOCALES_READY)
    }

    fun removeMultitapTimeout() {
        removeMessages(MSG_MULTITAP_TIMEOUT)
    }

    /**
     * Runs on every suggestion update, so it must never drop text bound for the editor (that is
     * [cancelPendingTextCommits], teardown only). No-op during [suggestionDeliveryDepth].
     */
    fun cancelPendingSuggestionUpdates() {
        if (suggestionDeliveryDepth > 0) return
        removeMessages(MSG_UPDATE_SUGGESTIONS)
        removeMessages(MSG_UPDATE_GESTURE_SUGGESTIONS)
        removeMessages(MSG_UPDATE_JAPANESE_SUGGESTIONS)
    }

    /** Teardown only: drop queued text writes so they cannot land in the next editor. */
    fun cancelPendingTextCommits() {
        removeMessages(MSG_BATCH_INPUT_SUGGESTIONS)
        removeMessages(MSG_COMMIT_TEXT)
    }

    private inline fun deliveringSuggestions(block: () -> Unit) {
        suggestionDeliveryDepth++
        try {
            block()
        } finally {
            suggestionDeliveryDepth--
        }
    }

    fun hasPendingSuggestionUpdate(): Boolean = hasMessages(MSG_UPDATE_SUGGESTIONS)

    fun cancelPendingSuggestionDelivery() {
        removeMessages(MSG_SHOW_SUGGESTIONS)
    }

    fun hasPendingJapaneseSuggestionUpdate(): Boolean = hasMessages(MSG_UPDATE_JAPANESE_SUGGESTIONS)

    fun hasPendingAdditionalLocalesLoad(): Boolean = hasMessages(MSG_LOAD_ADDITIONAL_LOCALES)

    fun scheduleShiftStateUpdate() {
        removeMessages(MSG_UPDATE_SHIFT_STATE)
        sendMessageDelayed(obtainMessage(MSG_UPDATE_SHIFT_STATE), shiftStateUpdateDelay.toLong())
    }

    /**
     * Main-thread callers are in the current session.
     * @param c0666ac the suggestions to show
     */
    fun postShowSuggestions(c0666ac: SuggestedWords) {
        postShowSuggestions(c0666ac, getOwner()?.getInputLogic()?.getSessionGeneration() ?: 0)
    }

    /**
     * Sync/gesture producer. [sessionGeneration] is the session the request was dispatched under
     * (the worker may post after teardown); carried in arg2 and checked at delivery.
     * @param c0666ac the suggestions to show
     */
    fun postShowSuggestions(c0666ac: SuggestedWords, sessionGeneration: Int) {
        removeMessages(MSG_SHOW_SUGGESTIONS)
        obtainMessage(MSG_SHOW_SUGGESTIONS, 2, sessionGeneration, c0666ac).sendToTarget()
    }

    /**
     * Main-thread callers are in the current session.
     * @param c0666ac the suggestions to show
     */
    fun postShowSuggestionStrip(c0666ac: SuggestedWords) {
        postShowSuggestionStrip(c0666ac, getOwner()?.getInputLogic()?.getSessionGeneration() ?: 0)
    }

    /**
     * Async typed-path producer; must stamp the session too, or every reply it posts is dropped.
     * @param c0666ac the suggestions to show
     */
    fun postShowSuggestionStrip(c0666ac: SuggestedWords, sessionGeneration: Int) {
        removeMessages(MSG_SHOW_SUGGESTIONS)
        obtainMessage(MSG_SHOW_SUGGESTIONS, 0, sessionGeneration, c0666ac).sendToTarget()
    }

    /**
     * @param c0666ac the gesture's suggestions; the top word is committed
     * @param enumC0690f the input source of the gesture
     */
    fun postBatchInputSuggestions(c0666ac: SuggestedWords, enumC0690f: InputSource) {
        obtainMessage(MSG_BATCH_INPUT_SUGGESTIONS, enumC0690f.getId(), 0, c0666ac).sendToTarget()
    }

    fun resetOrientationState() {
        removeMessages(MSG_ORIENTATION_DELAY)
        resetPendingInputFlags()
    }

    fun prepareForOrientationChange() {
        needsOrientationChange = true
        val blackBerryIMEV = getOwner() ?: return
        if (blackBerryIMEV.isInputViewShown()) {
            blackBerryIMEV.getKeyboardSwitcher().saveKeyboardState(blackBerryIMEV.getCurrentInputEditorInfo().inputType)
        }
    }

    private fun resetPendingInputFlags() {
        pendingFinishInputView = false
        pendingFinishInput = false
        pendingStartInput = false
    }

    /** Queued text commits are cancelled at view finish and view start. */
    private fun finishInputView(blackBerryIME: BlackBerryIME, finishingInput: Boolean) {
        cancelPendingTextCommits()
        blackBerryIME.finishInputViewInternal(finishingInput)
    }

    private fun startInputView(blackBerryIME: BlackBerryIME, editorInfo: EditorInfo?, restarting: Boolean) {
        cancelPendingTextCommits()
        blackBerryIME.startInputViewInternal(editorInfo, restarting)
    }

    /** @param z the restarting flag for a deferred start-input */
    private fun drainPendingInputEvents(blackBerryIME: BlackBerryIME, editorInfo: EditorInfo?, z: Boolean) {
        if (pendingFinishInputView) {
            finishInputView(blackBerryIME, pendingFinishInput)
        }
        if (pendingFinishInput) {
            blackBerryIME.finishInputInternal()
        }
        if (pendingStartInput) {
            blackBerryIME.startInputInternal(editorInfo, z)
        }
        resetPendingInputFlags()
    }

    /** @param z true when restarting input in the same editor */
    fun handleStartInput(editorInfo: EditorInfo?, z: Boolean) {
        if (hasMessages(MSG_ORIENTATION_DELAY)) {
            pendingStartInput = true
            return
        }
        val blackBerryIMEV = getOwner()
        if (needsOrientationChange && z) {
            needsOrientationChange = false
            shouldDelayInputStart = true
        }
        if (blackBerryIMEV != null) {
            drainPendingInputEvents(blackBerryIMEV, editorInfo, z)
            blackBerryIMEV.startInputInternal(editorInfo, z)
        }
    }

    /**
     * @param str first package name
     * @param str2 second package name
     */
    private fun equalsNullable(str: String?, str2: String?): Boolean {
        if (str == null && str2 == null) return true
        if (str == null || str2 == null) return false
        return str == str2
    }

    /** @param z true when restarting input in the same editor */
    @Throws(Resources.NotFoundException::class)
    fun handleStartInputView(editorInfo: EditorInfo?, z: Boolean) {
        if (editorInfo != null) {
            if (!equalsNullable(editorInfo.packageName, lastPackageName)) {
                val c1083eM6839q = KeyboardSwitcher.getInstance().getSlideboardManager()
                if (c1083eM6839q != null) {
                    c1083eM6839q.show()
                    c1083eM6839q.setTranslation(0.0f, false)
                }
            }
            lastPackageName = editorInfo.packageName
        }
        // Absorbs the redundant restart after an orientation change. Same editor instance, not just
        // equivalent inputType/imeOptions, or a real switch to a similar field would be lost.
        if (hasMessages(MSG_ORIENTATION_DELAY) && KeyboardId.sameEditor(editorInfo, lastEditorInfo)) {
            resetPendingInputFlags()
            return
        }
        if (shouldDelayInputStart) {
            shouldDelayInputStart = false
            resetPendingInputFlags()
            sendMessageDelayed(obtainMessage(MSG_ORIENTATION_DELAY), ORIENTATION_DELAY_MS)
        }
        val blackBerryIMEV = getOwner()
        if (blackBerryIMEV != null) {
            drainPendingInputEvents(blackBerryIMEV, editorInfo, z)
            startInputView(blackBerryIMEV, editorInfo, z)
            lastEditorInfo = editorInfo
        }
    }

    /** @param z true when input is finishing too, not just the view */
    fun handleFinishInputView(z: Boolean) {
        if (hasMessages(MSG_ORIENTATION_DELAY)) {
            pendingFinishInputView = true
            scheduleDeferredCleanupSafety()
            return
        }
        val blackBerryIMEV = getOwner()
        if (blackBerryIMEV != null) {
            finishInputView(blackBerryIMEV, z)
            lastEditorInfo = null
        }
    }

    fun handleFinishInput() {
        if (hasMessages(MSG_ORIENTATION_DELAY)) {
            pendingFinishInput = true
            scheduleDeferredCleanupSafety()
            return
        }
        val blackBerryIMEV = getOwner()
        if (blackBerryIMEV != null) {
            drainPendingInputEvents(blackBerryIMEV, null, false)
            blackBerryIMEV.finishInputInternal()
        }
    }

    private fun scheduleDeferredCleanupSafety() {
        removeMessages(MSG_DEFERRED_CLEANUP_SAFETY)
        sendMessageDelayed(obtainMessage(MSG_DEFERRED_CLEANUP_SAFETY), 1200L)
    }

    private fun executeDeferredCleanupSafety() {
        if (!pendingFinishInputView && !pendingFinishInput && !pendingStartInput) {
            return // Already drained by the normal start-input cycle
        }
        Logger.info(BlackBerryIME.LOG_TAG, "Deferred cleanup safety: flushing pending finish events")
        val blackBerryIMEV = getOwner()
        if (blackBerryIMEV != null) {
            if (pendingFinishInputView) {
                finishInputView(blackBerryIMEV, pendingFinishInput)
            }
            if (pendingFinishInput) {
                blackBerryIMEV.finishInputInternal()
            }
            if (pendingStartInput) {
                // A deferred start with no view start (window stayed hidden): run it now.
                blackBerryIMEV.startInputInternal(blackBerryIMEV.currentInputEditorInfo, true)
            }
        }
        resetPendingInputFlags()
    }
}
