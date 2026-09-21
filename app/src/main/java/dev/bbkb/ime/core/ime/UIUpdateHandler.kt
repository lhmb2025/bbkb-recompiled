package dev.bbkb.ime.core.ime
import android.content.res.Resources
import android.os.Message
import android.util.Log
import android.view.inputmethod.EditorInfo
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
        private const val MSG_CANCEL_QUICK_SWITCH = 13
        private const val MSG_UPDATE_GESTURE_SUGGESTIONS = 14
        private const val MSG_UPDATE_JAPANESE_SUGGESTIONS = 15
        private const val MSG_COMMIT_TEXT = 17
        private const val MSG_DEFERRED_CLEANUP_SAFETY = 99

        /**
         * How long start/finish-input events stay deferred after an orientation change — the
         * window LC-2 was fixed inside. Audit CT-26: [MSG_ORIENTATION_DELAY] was declared and
         * then never used; all five deferral sites wrote the raw literal `1`, so grepping for
         * the name found only the declaration.
         */
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

    fun initializeDelays() {
        val blackBerryIMEV = getOwner() ?: return
        val resources = blackBerryIMEV.getResources()
        // PKB Optimization: Use faster suggestion delay for physical keyboard devices
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
                    // Audit DW-3: a reply dispatched under a previous input session must not be
                    // delivered into this one. Dropping it HERE rather than filtering what it
                    // writes is what keeps the gesture-commit channel intact: mCurrentSuggestions
                    // doubles as that channel, and a gesture is not composing, so gating the
                    // cache write on isComposing() would starve gesture commit.
                    val currentGeneration = blackBerryIMEV.getInputLogic().getSessionGeneration()
                    if (message.arg2 != currentGeneration) {
                        if (BuildConfig.DEBUG) Log.d("SUGG",
                            "MSG_SHOW_SUGGESTIONS: DROPPING — dispatched in session ${message.arg2}," +
                                " now $currentGeneration (DW-3)")
                        return
                    }
                    if (message.arg1 != 0) {
                        blackBerryIMEV.displaySuggestions(words, false)
                    } else {
                        blackBerryIMEV.showSuggestionStrip(words)
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
                    blackBerryIMEV.displaySuggestions(words, true)
                    // Audit CT-6: the word list used to be built unconditionally here, on the
                    // main thread once per swipe, even though its only consumers are inside
                    // InputPathDebug.on() guards below — which fold to false in release.
                    val wordList: String = if (InputPathDebug.on()) {
                        val sb = StringBuilder()
                        for (i in 0 until words.size()) {
                            sb.append("[").append(i).append("]='").append(words.getWordInfo(i)?.word).append("' ")
                        }
                        sb.toString()
                    } else {
                        ""
                    }
                    // Audit EB-1: consume ONCE the gesture's prediction round-trip completes,
                    // on BOTH branches. It used to be consumed only when words.size() > 0, so
                    // a swipe that produced nothing (garbage swipe, or predictions disabled)
                    // left the flag set forever — and every later empty-composing request then
                    // skipped setContextBuffer AND the defensive clear() in the bridge,
                    // dropping sentence context from next-word predictions until a later
                    // gesture or a process restart.
                    //
                    // Audit DW-2: the return still matters. The flag is cleared on session
                    // teardown, so false means "this gesture belongs to a session that is
                    // gone" — display only, never commit.
                    // An empty result carries no sequence: consume whatever is pending (legacy
                    // path) instead of indexing word 0 of an empty list.
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
                MSG_CANCEL_QUICK_SWITCH -> {
                    if (BuildConfig.DEBUG) Log.i(BlackBerryIME.LOG_TAG, "Quick subtype switcher canceled")
                    blackBerryIMEV.cancelQuickSwitch()
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
                    // Audit CT-26: this was the only branch without a trailing return, so it fell
                    // through to the "message is unhandled" diagnostic below — a false report
                    // today and a double-execution bug the moment anything is added after the
                    // `when`.
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

    fun postCommitText(str: String) {
        sendMessage(obtainMessage(MSG_COMMIT_TEXT, str))
    }

    /**
     * Post [action] tagged with [token], so a single `removeCallbacksAndMessages(token)` cancels
     * every piece of work posted under it (audit CT-24: deferred startup work used to go through
     * a throwaway `Handler` that nothing could cancel at teardown).
     *
     * `Message.obtain(handler, callback)` dispatches the callback directly, so this never reaches
     * [handleMessage]; `Handler.postDelayed(Runnable, Object, long)` is API 28+, hence the message.
     */
    fun postTokenized(token: Any, delayMillis: Long, action: Runnable) {
        val message = Message.obtain(this, action)
        message.obj = token
        sendMessageDelayed(message, delayMillis)
    }

    fun postCancelQuickSwitch() {
        sendMessageDelayed(obtainMessage(MSG_CANCEL_QUICK_SWITCH), suggestionUpdateDelay.toLong())
    }

    fun postUpdateSuggestions(i: Int) {
        sendMessageDelayed(obtainMessage(MSG_UPDATE_SUGGESTIONS, i, 0), suggestionUpdateDelay.toLong())
    }

    fun postUpdateJapaneseSuggestions(i: Int) {
        sendMessageDelayed(obtainMessage(MSG_UPDATE_JAPANESE_SUGGESTIONS, i, 0), suggestionUpdateDelay.toLong())
    }

    fun postUpdateGestureSuggestions(i: Int) {
        sendMessageDelayed(obtainMessage(MSG_UPDATE_GESTURE_SUGGESTIONS, i, 0), gestureInputSuggestionDelay.toLong())
    }

    fun postLoadAdditionalLocales() {
        sendMessage(obtainMessage(MSG_LOAD_ADDITIONAL_LOCALES))
    }

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

    fun cancelPendingSuggestionUpdates() {
        removeMessages(MSG_UPDATE_SUGGESTIONS)
        removeMessages(MSG_UPDATE_GESTURE_SUGGESTIONS)
        removeMessages(MSG_UPDATE_JAPANESE_SUGGESTIONS)
        // Audit DW-2: this is the only message that COMMITS TEXT, and it had no
        // removeMessages site anywhere — a queued gesture commit outlived the field it was
        // typed in. It belongs in the same cancel set as the updates that produce it.
        removeMessages(MSG_BATCH_INPUT_SUGGESTIONS)
        // Audit DW-5/DW-8: neither of these had a removeMessages site anywhere.
        // MSG_COMMIT_TEXT writes text into whatever editor is current at delivery (voice
        // board); MSG_CANCEL_QUICK_SWITCH hides the keyboard on a decision that may be stale
        // by the time it lands. Both belong in the teardown cancel set.
        removeMessages(MSG_COMMIT_TEXT)
        removeMessages(MSG_CANCEL_QUICK_SWITCH)
    }

    fun hasPendingSuggestionUpdate(): Boolean = hasMessages(MSG_UPDATE_SUGGESTIONS)

    fun hasPendingJapaneseSuggestionUpdate(): Boolean = hasMessages(MSG_UPDATE_JAPANESE_SUGGESTIONS)

    fun hasPendingAdditionalLocalesLoad(): Boolean = hasMessages(MSG_LOAD_ADDITIONAL_LOCALES)

    fun scheduleShiftStateUpdate() {
        removeMessages(MSG_UPDATE_SHIFT_STATE)
        sendMessageDelayed(obtainMessage(MSG_UPDATE_SHIFT_STATE), shiftStateUpdateDelay.toLong())
    }

    fun postShowSuggestions(c0666ac: SuggestedWords) {
        // Main-thread callers are by definition in the current session.
        postShowSuggestions(c0666ac, getOwner()?.getInputLogic()?.getSessionGeneration() ?: 0)
    }

    /**
     * Audit DW-3: [sessionGeneration] is the session the REQUEST was dispatched under, not the
     * one current when it is posted — the worker posts after any teardown, so stamping at post
     * time would always look current and drop nothing. Carried in arg2 and checked at delivery.
     */
    fun postShowSuggestions(c0666ac: SuggestedWords, sessionGeneration: Int) {
        removeMessages(MSG_SHOW_SUGGESTIONS)
        obtainMessage(MSG_SHOW_SUGGESTIONS, 2, sessionGeneration, c0666ac).sendToTarget()
    }

    fun postShowSuggestionStrip(c0666ac: SuggestedWords) {
        // Main-thread callers are by definition in the current session.
        postShowSuggestionStrip(c0666ac, getOwner()?.getInputLogic()?.getSessionGeneration() ?: 0)
    }

    /**
     * Audit DW-3. This is the OTHER producer of MSG_SHOW_SUGGESTIONS — the async typed path
     * delivers through here, `postShowSuggestions` is the sync/gesture one. Both must stamp
     * the session, because the handler drops anything whose stamp is not current: gating the
     * message while leaving a producer unstamped drops every reply it posts.
     */
    fun postShowSuggestionStrip(c0666ac: SuggestedWords, sessionGeneration: Int) {
        removeMessages(MSG_SHOW_SUGGESTIONS)
        obtainMessage(MSG_SHOW_SUGGESTIONS, 0, sessionGeneration, c0666ac).sendToTarget()
    }

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
        blackBerryIMEV.getInputLogic().setDynamicLearningEnabled(false)
        if (blackBerryIMEV.isInputViewShown()) {
            blackBerryIMEV.getKeyboardSwitcher().saveKeyboardState(blackBerryIMEV.getCurrentInputEditorInfo().inputType)
        }
    }

    private fun resetPendingInputFlags() {
        pendingFinishInputView = false
        pendingFinishInput = false
        pendingStartInput = false
    }

    private fun drainPendingInputEvents(blackBerryIME: BlackBerryIME, editorInfo: EditorInfo?, z: Boolean) {
        if (pendingFinishInputView) {
            blackBerryIME.finishInputViewInternal(pendingFinishInput)
        }
        if (pendingFinishInput) {
            blackBerryIME.finishInputInternal()
        }
        if (pendingStartInput) {
            blackBerryIME.startInputInternal(editorInfo, z)
        }
        resetPendingInputFlags()
    }

    fun handleStartInput(editorInfo: EditorInfo?, z: Boolean) {
        if (hasMessages(MSG_ORIENTATION_DELAY)) {
            pendingStartInput = true
            return
        }
        val blackBerryIMEV = getOwner()
        if (needsOrientationChange && z) {
            needsOrientationChange = false
            shouldDelayInputStart = true
            blackBerryIMEV?.getInputLogic()?.setDynamicLearningEnabled(blackBerryIMEV.getSettingsManager().getSettingsValues().isDynamicLearningEnabled)
        }
        if (blackBerryIMEV != null) {
            drainPendingInputEvents(blackBerryIMEV, editorInfo, z)
            blackBerryIMEV.startInputInternal(editorInfo, z)
        }
    }

    private fun equalsNullable(str: String?, str2: String?): Boolean {
        if (str == null && str2 == null) return true
        if (str == null || str2 == null) return false
        return str == str2
    }

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
        // Audit LC-2: this swallow exists to absorb the redundant restart that follows an
        // orientation change. It used to test only equivalentEditorInfo, which is true for
        // ANY two fields sharing inputType/imeOptions — so a genuine switch to a similar
        // field inside the 800 ms window was silently discarded along with its deferred
        // finish/start work. Require the same editor instance instead.
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
            blackBerryIMEV.startInputViewInternal(editorInfo, z)
            lastEditorInfo = editorInfo
        }
    }

    fun handleFinishInputView(z: Boolean) {
        if (hasMessages(MSG_ORIENTATION_DELAY)) {
            pendingFinishInputView = true
            scheduleDeferredCleanupSafety()
            return
        }
        val blackBerryIMEV = getOwner()
        if (blackBerryIMEV != null) {
            blackBerryIMEV.finishInputViewInternal(z)
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
        // Audit LC-11: pendingStartInput was set by handleStartInput during the deferral
        // window but had no branch here, so a start deferred with no onStartInputView
        // following (window stays hidden) silently dropped superOnStartInput, work-profile
        // detection and the keyboard-reload decision for that editor.
        if (!pendingFinishInputView && !pendingFinishInput && !pendingStartInput) {
            return // Already consumed by CommitType normal onStartInput cycle
        }
        Logger.info(BlackBerryIME.LOG_TAG, "Deferred cleanup safety: flushing pending finish events")
        val blackBerryIMEV = getOwner()
        if (blackBerryIMEV != null) {
            if (pendingFinishInputView) {
                blackBerryIMEV.finishInputViewInternal(pendingFinishInput)
            }
            if (pendingFinishInput) {
                blackBerryIMEV.finishInputInternal()
            }
            if (pendingStartInput) {
                // Deferred start never got its view-start; run it now so the editor is not
                // left half-initialised (audit LC-11).
                blackBerryIMEV.startInputInternal(blackBerryIMEV.currentInputEditorInfo, true)
            }
        }
        resetPendingInputFlags()
    }
}
