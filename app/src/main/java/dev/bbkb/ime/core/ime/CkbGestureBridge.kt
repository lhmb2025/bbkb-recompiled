package dev.bbkb.ime.core.ime
import android.content.SharedPreferences
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver
import dev.bbkb.ime.core.gesture.arbiter.FirmwareGestureKeys
import dev.bbkb.ime.core.gesture.arbiter.GestureAction
import dev.bbkb.ime.core.gesture.arbiter.GestureAssignments
import dev.bbkb.ime.core.gesture.arbiter.GestureClassification
import dev.bbkb.ime.core.gesture.arbiter.GestureClassifier
import dev.bbkb.ime.core.gesture.arbiter.GestureConfig
import dev.bbkb.ime.core.gesture.arbiter.GesturePolicy
import dev.bbkb.ime.core.gesture.arbiter.GestureTrace
import dev.bbkb.ime.core.gesture.arbiter.GestureTraceRecorder
import dev.bbkb.ime.core.gesture.arbiter.KeyTiming
import dev.bbkb.ime.core.gesture.arbiter.KeyTypingGuard
import dev.bbkb.ime.core.gesture.arbiter.ModeState
import dev.bbkb.ime.core.gesture.arbiter.PolicyOutcome
import dev.bbkb.ime.core.gesture.arbiter.TracePoint
import dev.bbkb.ime.core.keyevent.InputSource
import dev.bbkb.ime.core.suggestion.SuggestedWords
import dev.bbkb.ime.core.shared.InputPathDebug
import dev.bbkb.ime.core.shared.Logger
import dev.bbkb.ime.core.settings.PrefsManager
import kotlin.math.hypot
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.R
import dev.bbkb.ime.keyboard.internal.EngineTouch

/** Slots on the suggestion strip; the strip shows suggestion i in slot i, left to right. */
internal const val STRIP_SLOT_COUNT = 3

/**
 * Index a flick-up commits: [normalizedX] is the flick start across the keypad (0..1, RTL-mirrored).
 * Slot maps straight to index; an empty slot commits the last visible word. -1 when nothing to commit.
 */
internal fun flickCommitIndex(normalizedX: Float, suggestionCount: Int): Int {
    if (suggestionCount <= 0) return -1
    val visibleCount = minOf(suggestionCount, STRIP_SLOT_COUNT)
    val slot = (STRIP_SLOT_COUNT * normalizedX.coerceIn(0f, 1f)).toInt()
    return slot.coerceIn(0, visibleCount - 1)
}

/**
 * Capacitive-keypad gesture arbiter to IME: records and classifies each contact, resolves it via
 * [GesturePolicy] and executes the [GestureAction]. Also owns the cursor-mode drag.
 * [BlackBerryIME.onGenericMotionEvent] gates keypad events, calls [onContactStart] on every
 * (pointer) DOWN, then routes to [handleCursorModeDrag] or [feed].
 */
class CkbGestureBridge(private val ime: BlackBerryIME) {

    private companion object {
        const val TAG = "CKB_NEW_ENGINE"
        /** Movement beyond this many device px makes a cursor-mode contact a drag, not a tap. */
        const val CURSOR_TAP_SLOP_PX = 30.0
    }

    /** Set once the arbiter has handled (and consumed) the current contact; reset on DOWN. */
    private var contactConsumed = false

    /**
     * Suggestions when the contact began. A flick's DOWN/MOVE feed the flow pipeline before it is
     * classified at UP, which can clear the live list; commit from this snapshot.
     */
    private var startSuggestions: SuggestedWords? = null

    /**
     * The composing word this contact's samples committed by starting a swipe ([BlackBerryIME.onStartBatchInput]).
     * The flick is recognised only at UP, so the pick must take it back to replace it. Null when none.
     */
    private var wordCommittedBySwipeStart: String? = null

    private val policy = GesturePolicy()

    /** Typing windows: the noise window shares the key detector's DOWN-filter resource. */
    private val keyGuard = KeyTypingGuard(
        noiseWindowMs = { ime.resources.getInteger(R.integer.config_ckb_touch_noise_threshold_time).toLong() },
        suppressionWindowMs = { ime.getSettingsManager().getSettingsValues().ckbGestureSuppressionTimeout.toLong() },
        measureFromEnd = { ime.getSettingsManager().getSettingsValues().applySwipeSuppressionToEnd },
    )

    private val recorder: GestureTraceRecorder by lazy {
        GestureTraceRecorder(
            configProvider = { gestureConfig() },
            listener = object : GestureTraceRecorder.Listener {
                override fun onTraceComplete(trace: GestureTrace, previousTap: GestureTrace?) {
                    keyGuard.onContactEnd()
                    if (contactConsumed) return // already handled mid-contact (e.g. by hold)
                    val verdict = GestureClassifier(gestureConfig()).classify(trace, previousTap)
                    val keyTiming = keyGuard.keyTiming(trace.end.t)
                    val outcome = policy.resolve(verdict, currentModeState(keyTiming), gestureAssignments())
                    // Guarded: Logger.info builds its argument before checking DEBUG.
                    if (InputPathDebug.perGesture()) {
                        Logger.info(
                            TAG,
                            "verdict=${verdict.label} -> ${outcome.label}" +
                                (if (keyTiming.suppressesGestures) " [typing: $keyTiming]" else "") +
                                " | disp=${"%.3f".format(trace.displacement)}" +
                                " straight=${"%.2f".format(trace.straightness)}" +
                                " meanSpd=${"%.2f".format(trace.meanSpeed)}" +
                                " dur=${trace.durationMs}ms"
                        )
                    }
                    if (outcome is PolicyOutcome.Act) {
                        executeGestureAction(outcome.action, trace)
                        contactConsumed = outcome.action.consumes
                    }
                }

                override fun onHold(trace: GestureTrace) {
                    if (contactConsumed) return
                    val keyTiming = keyGuard.keyTiming(trace.end.t)
                    val outcome = policy.resolve(GestureClassification.Hold, currentModeState(keyTiming), gestureAssignments())
                    if (InputPathDebug.perGesture()) Logger.info(TAG, "HOLD detected -> ${outcome.label}")
                    if (outcome is PolicyOutcome.Act) {
                        executeGestureAction(outcome.action, trace)
                        contactConsumed = outcome.action.consumes
                    }
                }
            }
        )
    }

    /**
     * DOWN and POINTER_DOWN: chained flicks overlap, and the recorder starts a fresh trace per
     * finger, so the consumed flag and suggestion snapshot must reset here too.
     */
    fun onContactStart(eventTime: Long) {
        flushCursorSteps()
        engineTrackingCancelled = false
        contactConsumed = false
        keyGuard.onContactStart(eventTime)
        startSuggestions = ime.getInputLogic().mCurrentSuggestions
        wordCommittedBySwipeStart = null
    }

    fun onSwipeStartCommittedTypedWord(word: String) {
        wordCommittedBySwipeStart = word
    }

    /** Every hardware key down/up, so the guard sees typing whether or not the input view is ready. */
    fun onHardwareKey(eventTime: Long) {
        // A key typed mid-drag must land where the drag has already put the cursor.
        flushCursorSteps()
        keyGuard.onKeyEvent(eventTime)
    }

    /** True when an arbiter action consumed the contact (caller cancels flow, swallows the event); false falls through. */
    fun feed(event: MotionEvent): Boolean {
        recorder.onMotionEvent(event)
        return contactConsumed
    }

    /**
     * A firmware-synthesised swipe key with a gesture role ([FirmwareGestureKeys]; Titan 2 keycodes
     * 322 and 404) runs the swipe-left slot; press, repeats and release are all consumed.
     */
    fun handleFirmwareGestureKey(event: KeyEvent): Boolean {
        val mapping = DeviceInputResolver.keyRole(event.scanCode, event.keyCode, event.deviceId)
            ?: return false
        if (FirmwareGestureKeys.swipeFor(mapping.role) == null) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && ime.isInputActive) {
            val outcome = FirmwareGestureKeys.outcome(
                mapping.role, currentModeState(KeyTiming.CLEAR), gestureAssignments(), policy)
            if (InputPathDebug.perGesture()) Logger.info(TAG, "firmware ${mapping.role} -> ${outcome?.label}")
            if (outcome is PolicyOutcome.Act) {
                executeGestureAction(outcome.action,
                    GestureTrace(listOf(TracePoint(0.5f, 0.5f, event.eventTime))))
            }
        }
        return true
    }

    // Cursor-mode drag, in raw device px.
    private var dragLastX = 0f
    private var dragLastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var accumX = 0f
    private var accumY = 0f
    private var dragDownTime = 0L
    private var dragLastTime = 0L
    private var dragMoved = false
    private var dragInitialized = false

    private var engineTrackingCancelled = false

    /**
     * Cursor steps queued for the next frame (positive: left/up). One axis and direction at a time:
     * a change flushes first, so clamping at the text ends matches step-by-step moves.
     */
    private var pendingHorizontalSteps = 0
    private var pendingVerticalSteps = 0
    private var flushPosted = false
    private val flushCallback = Choreographer.FrameCallback {
        flushPosted = false
        flushCursorSteps()
    }

    /**
     * Cancel the engine's tracking once per contact (first cursor-mode or consumed sample); true when
     * it did. Names every pointer, since cursor mode can start mid-contact or with a second finger.
     */
    fun cancelEngineTrackingOncePerContact(event: MotionEvent): Boolean {
        if (engineTrackingCancelled) return false
        engineTrackingCancelled = true
        // Read here on the main thread: the MotionEvent is recycled after dispatch.
        val pointerIds = LongArray(event.pointerCount) { event.getPointerId(it).toLong() }
        EngineTouch.cancelAll("ckbContact.touchCancel", pointerIds)
        return true
    }

    /**
     * Drag-to-move-cursor in cursor (FCC) mode with velocity-scaled accumulation. A stationary tap
     * exits cursor mode; entered mid-contact, it initialises on the first move so the cursor does not jump.
     */
    fun handleCursorModeDrag(event: MotionEvent) {
        val idx = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> initDrag(event, idx)
            MotionEvent.ACTION_MOVE -> {
                if (!dragInitialized) {
                    initDrag(event, idx)
                } else {
                    val x = event.getX(idx)
                    val y = event.getY(idx)
                    moveCursorByScroll(event, dragLastX - x, dragLastY - y)
                    dragLastX = x
                    dragLastY = y
                    if (hypot((x - downX).toDouble(), (y - downY).toDouble()) > CURSOR_TAP_SLOP_PX) dragMoved = true
                }
            }
            MotionEvent.ACTION_UP -> {
                flushCursorSteps()
                if (dragInitialized && !dragMoved) ime.toggleCursorMode()
                dragInitialized = false
            }
            MotionEvent.ACTION_CANCEL -> {
                flushCursorSteps()
                dragInitialized = false
            }
        }
    }

    private fun initDrag(event: MotionEvent, idx: Int) {
        dragLastX = event.getX(idx); downX = dragLastX
        dragLastY = event.getY(idx); downY = dragLastY
        accumX = 0f; accumY = 0f
        dragDownTime = event.eventTime; dragLastTime = event.eventTime
        dragMoved = false; dragInitialized = true
    }

    private fun moveCursorByScroll(event: MotionEvent, distanceX: Float, distanceY: Float) {
        val sv = ime.getSettingsManager().getSettingsValues()
        val eventTime = event.eventTime
        val jMax = eventTime - maxOf(dragDownTime, dragLastTime)
        dragLastTime = eventTime
        val maxMult = sv.maxCursorMoveSpeedMultiplier
        val pointerCount = event.pointerCount
        var dMin = 1.0
        if (Math.abs(distanceX) > Math.abs(distanceY)) {
            if (jMax > 0) dMin = Math.min(maxMult.toDouble(), Math.max(1.0, ((Math.abs(distanceX * 1000f / jMax) * maxMult) / sv.velocityForMaxCursorMoveSpeed).toDouble()))
            accumX += (pointerCount * distanceX * dMin).toFloat()
            val threshold = sv.horizontalScrollDistanceForCursorMove
            val steps = (accumX / threshold).toInt()
            accumX -= steps * threshold
            if (steps != 0) {
                accumY = 0f
                queueCursorSteps(steps, horizontal = true)
                dragMoved = true
            }
        } else {
            if (jMax > 0) dMin = Math.min(maxMult.toDouble(), Math.max(1.0, ((Math.abs(distanceY * 1000f / jMax) * maxMult) / sv.velocityForMaxCursorMoveSpeed).toDouble()))
            accumY += (pointerCount * distanceY * dMin).toFloat()
            val ei = ime.currentInputEditorInfo
            val singleLine = ei != null &&
                (ei.inputType and android.text.InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) == 0 &&
                (ei.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0
            val threshold = if (singleLine) sv.singleLineVerticalScrollForCursorMove else sv.verticalScrollDistanceForCursorMove
            val steps = (accumY / threshold).toInt()
            accumY -= steps * threshold
            if (steps != 0) {
                accumX = 0f
                queueCursorSteps(steps, horizontal = false)
                dragMoved = true
            }
        }
    }

    /** Queue [steps] for the next frame: each move is an editor round-trip and the pad sends several samples a frame. */
    private fun queueCursorSteps(steps: Int, horizontal: Boolean) {
        val pending = if (horizontal) pendingHorizontalSteps else pendingVerticalSteps
        val otherAxisPending = if (horizontal) pendingVerticalSteps else pendingHorizontalSteps
        if (otherAxisPending != 0 || (pending != 0 && (pending > 0) != (steps > 0))) flushCursorSteps()
        if (horizontal) pendingHorizontalSteps += steps else pendingVerticalSteps += steps
        if (!flushPosted) {
            flushPosted = true
            Choreographer.getInstance().postFrameCallback(flushCallback)
        }
    }

    private fun flushCursorSteps() {
        if (flushPosted) {
            flushPosted = false
            Choreographer.getInstance().removeFrameCallback(flushCallback)
        }
        val h = pendingHorizontalSteps
        val v = pendingVerticalSteps
        pendingHorizontalSteps = 0
        pendingVerticalSteps = 0
        if (h > 0) ime.moveLeft(h) else if (h < 0) ime.moveRight(-h)
        if (v > 0) ime.moveUp(v) else if (v < 0) ime.moveDown(-v)
    }

    // Policy inputs, cached because gestureConfig() runs on every ACTION_MOVE; any pref change
    // invalidates them so Gesture Lab tuning applies without a restart.
    private var cachedConfig: GestureConfig? = null
    private var cachedAssignments: GestureAssignments? = null
    private var cachedGesturesEnabled: Boolean? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        cachedConfig = null
        cachedAssignments = null
        cachedGesturesEnabled = null
    }
    private var prefsListenerRegistered = false

    /** Registers the listener lazily: this bridge is built during IME field init, before `PrefsManager.init()`. */
    private fun prefs(): SharedPreferences {
        val p = PrefsManager.getPrefs(ime)
        if (!prefsListenerRegistered) {
            p.registerOnSharedPreferenceChangeListener(prefsListener)
            prefsListenerRegistered = true
        }
        return p
    }

    fun release() {
        if (flushPosted) {
            flushPosted = false
            Choreographer.getInstance().removeFrameCallback(flushCallback)
        }
        pendingHorizontalSteps = 0
        pendingVerticalSteps = 0
        if (prefsListenerRegistered) {
            PrefsManager.getPrefs(ime).unregisterOnSharedPreferenceChangeListener(prefsListener)
            prefsListenerRegistered = false
        }
        cachedConfig = null
        cachedAssignments = null
        cachedGesturesEnabled = null
    }

    private fun currentModeState(keyTiming: KeyTiming): ModeState {
        val gesturesEnabled = cachedGesturesEnabled
            ?: prefs().getBoolean("ckb_gestures_enabled", true).also { cachedGesturesEnabled = it }
        return ModeState(
            gesturesEnabled = gesturesEnabled,
            swipeTypingEnabled = ime.getSettingsManager().getSettingsValues().isCkbGestureInputEnabled,
            fccActive = ime.fccController?.isViewActive() ?: false,
            composing = ime.getInputLogic().mComposingTracker.isComposing,
            keyTiming = keyTiming,
        )
    }

    /** An unset slot reads as the device profile's `default-value`, else the app-wide default. */
    private fun gestureAssignments(): GestureAssignments =
        cachedAssignments ?: GestureAssignments.fromPrefs(prefs(), GestureAssignments.deviceDefaults())
            .also { cachedAssignments = it }

    private fun gestureConfig(): GestureConfig =
        cachedConfig ?: GestureConfig.fromPrefs(prefs()).also { cachedConfig = it }

    /** [trace] gives the position (suggestion slot, delete sweep). */
    private fun executeGestureAction(action: GestureAction, trace: GestureTrace) {
        if (InputPathDebug.perGesture()) Logger.info(TAG, "execute ${action.key}")
        when (action) {
            GestureAction.NONE -> {}
            GestureAction.COMMIT_SUGGESTION -> commitSuggestionAt(trace.start.x)
            // Not deleteSurroundingText: the editor deletes around a composing region, not it.
            // A firmware key has no trace, so it gets a fixed right-to-left sweep.
            GestureAction.DELETE_WORD ->
                if (trace.isDegenerate) ime.onCkbSwipeDelete(0.75f, 0.25f)
                else ime.onCkbSwipeDelete(trace.start.x, trace.end.x)
            GestureAction.ENTER_CURSOR_MODE -> ime.toggleCursorMode()
            GestureAction.NEXT_LANGUAGE -> ime.updateSuggestionsFromSubtype(InputSource.SOFTWARE)
            GestureAction.CYCLE_SYMBOLS -> {
                // Leaving the Alt page clears Alt state only; Shift and held keys stay.
                ime.getPhysicalKeyboardStateTracker()
                    .resetModifiers(dev.bbkb.ime.core.keyevent.ModifierResetReason.ALT_PAGE_LEFT)
                ime.getKeyboardSwitcher().onSymbolShiftToggle(ime.getCurrentInputType(), ime.getCurrentImeOptions(), true, true)
            }
            GestureAction.DISMISS_KEYBOARD -> ime.dismissKeyboard()
            GestureAction.EMOJI, GestureAction.UNDO ->
                Logger.warn(TAG, "action ${action.key} not yet implemented")
        }
    }

    private fun commitSuggestionAt(normalizedXraw: Float): Boolean {
        val suggestions = startSuggestions ?: ime.getInputLogic().mCurrentSuggestions
        if (suggestions.isEmpty() || suggestions.size() == 0) {
            if (InputPathDebug.perGesture()) Logger.info(TAG, "commit_suggestion NO-OP: empty suggestions (snapshotWasNull=${startSuggestions == null})")
            return false
        }
        var normalizedX = normalizedXraw.coerceIn(0f, 1f)
        if (ime.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) normalizedX = 1f - normalizedX
        val slot = flickCommitIndex(normalizedX, suggestions.size())
        val wordInfo = if (slot < 0) null else suggestions.getWordInfo(slot)
        if (wordInfo == null) {
            if (InputPathDebug.perGesture()) Logger.info(TAG, "commit_suggestion NO-OP: null wordInfo slot=$slot size=${suggestions.size()}")
            return false
        }
        if (InputPathDebug.perGesture()) Logger.info(TAG, "commit_suggestion OK: slot=$slot of ${suggestions.size()}")
        // The pick replaces the typed word even if this flick's swipe start committed it.
        val committed = wordCommittedBySwipeStart
        wordCommittedBySwipeStart = null
        if (committed != null) ime.getInputLogic().takeBackWordCommittedForSwipe(committed)
        ime.onSuggestionPicked(wordInfo, InputSource.SOFTWARE)
        if (ime.getSettingsManager().getSettingsValues().flickCommitAnimationEnabled) {
            ime.auxBarManager?.getAuxBarView()?.getSuggestionView()?.flashCommittedSlot(slot)
        }
        return true
    }
}
