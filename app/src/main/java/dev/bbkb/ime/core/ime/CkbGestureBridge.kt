package dev.bbkb.ime.core.ime
import android.content.SharedPreferences
import android.view.MotionEvent
import android.view.View
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
import dev.bbkb.ime.core.keyevent.InputSource
import dev.bbkb.ime.core.suggestion.SuggestedWords
import dev.bbkb.ime.core.shared.InputPathDebug
import dev.bbkb.ime.core.shared.Logger
import dev.bbkb.ime.core.settings.PrefsManager
import kotlin.math.hypot
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.R

/**
 * The capacitive-keypad (CKB) gesture arbiter's connection to the IME: feeds each contact to the
 * [GestureTraceRecorder], classifies it, resolves the user's slot assignments through
 * [GesturePolicy], and executes the resulting [GestureAction] against the IME. Also owns the
 * cursor-mode drag (continuous drag-to-move-cursor while the arrow bar is up).
 *
 * [BlackBerryIME.onGenericMotionEvent] remains the gate: it decides whether an event is a keypad
 * event at all, calls [onContactStart] on every (pointer) DOWN, and then either routes the contact
 * to [handleCursorModeDrag] (cursor mode) or to [feed] (everything else).
 */
class CkbGestureBridge(private val ime: BlackBerryIME) {

    private companion object {
        const val TAG = "CKB_NEW_ENGINE"
        const val STRIP_SLOT_COUNT = 3
        /** Movement beyond this many device px makes a cursor-mode contact a drag, not a tap. */
        const val CURSOR_TAP_SLOP_PX = 30.0
    }

    /** Set once the arbiter has handled (and consumed) the current contact; reset on DOWN. */
    private var contactConsumed = false

    /**
     * Suggestions as they were when the contact began. A flick-up's own DOWN/MOVE feed the flow
     * pipeline (touchStart/touchMove) before we classify it as a swipe at UP, which can clear the
     * live suggestions — so commit from this snapshot to commit what was actually displayed.
     */
    private var startSuggestions: SuggestedWords? = null

    /** Pure policy that maps a classified gesture + mode + assignments to an outcome. */
    private val policy = GesturePolicy()

    /**
     * The original's typing windows, re-homed in the arbiter. The noise window is the same
     * resource the key detector's DOWN filter reads; the suppression window and its anchor are
     * the two preferences the legacy listener consulted (still on the gesture-timing screens).
     */
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
                    // Audit CT-7: build the message only when the per-gesture gate is open.
                    // Logger.info evaluates its argument first and consults BuildConfig.DEBUG
                    // last, so an unguarded call site pays three String.format calls plus the
                    // interpolation on every completed contact, in release too.
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
     * A new contact (DOWN, and POINTER_DOWN too: chained flicks overlap contacts, and the recorder
     * starts a fresh trace on the new finger — its consumed flag and suggestion snapshot must
     * reset with it or the second flick is swallowed / commits stale words). Snapshot the strip
     * before this contact's events can disturb the flow pipeline.
     */
    fun onContactStart(eventTime: Long) {
        contactConsumed = false
        keyGuard.onContactStart(eventTime)
        startSuggestions = ime.getInputLogic().mCurrentSuggestions
    }

    /**
     * A hardware key went down or up. Fed from the IME's key entry points for every key, so the
     * guard sees typing whether or not the input view or gesture input is ready — the original's
     * windows were keyed the same way.
     */
    fun onHardwareKey(eventTime: Long) = keyGuard.onKeyEvent(eventTime)

    /**
     * Feed the recorder; its listener acts synchronously. Returns true when the contact has been
     * consumed by an arbiter action (the caller cancels any in-progress flow and swallows the
     * event); false means Tap / FlowTrace / pre-UP, to fall through to the shared key/flow path.
     */
    fun feed(event: MotionEvent): Boolean {
        recorder.onMotionEvent(event)
        return contactConsumed
    }

    // ------------------------------------------------------------------ cursor-mode drag
    // Raw device coords (thresholds are device-px).

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

    /**
     * Continuous drag-to-move-cursor while cursor (FCC) mode is active, replicating the legacy
     * onScroll feel (velocity-scaled accumulation → CursorMovementListener). A stationary tap
     * exits cursor mode. Handles entering cursor mode mid-contact (initializes on the first move
     * so the cursor doesn't jump).
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
                // A tap (no real movement) in cursor mode exits it.
                if (dragInitialized && !dragMoved) ime.toggleCursorMode()
                dragInitialized = false
            }
            MotionEvent.ACTION_CANCEL -> dragInitialized = false
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
                if (steps > 0) ime.moveLeft(steps) else ime.moveRight(-steps)
                dragMoved = true
            }
        } else {
            if (jMax > 0) dMin = Math.min(maxMult.toDouble(), Math.max(1.0, ((Math.abs(distanceY * 1000f / jMax) * maxMult) / sv.velocityForMaxCursorMoveSpeed).toDouble()))
            accumY += (pointerCount * distanceY * dMin).toFloat()
            val ei = ime.currentInputEditorInfo
            // Audit CT-22: 0x40000 / 0x20000 are TYPE_TEXT_FLAG_IME_MULTI_LINE / TYPE_TEXT_FLAG_MULTI_LINE.
            val singleLine = ei != null &&
                (ei.inputType and android.text.InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) == 0 &&
                (ei.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0
            val threshold = if (singleLine) sv.singleLineVerticalScrollForCursorMove else sv.verticalScrollDistanceForCursorMove
            val steps = (accumY / threshold).toInt()
            accumY -= steps * threshold
            if (steps != 0) {
                accumX = 0f
                if (steps > 0) ime.moveUp(steps) else ime.moveDown(-steps)
                dragMoved = true
            }
        }
    }

    // ------------------------------------------------------------------ policy inputs
    //
    // Audit CT-1/CT-17/GD-1: these three used to call
    // PreferenceManager.getDefaultSharedPreferences(ime) on every invocation, and
    // gestureConfig() is the recorder's configProvider — so it ran on EVERY ACTION_MOVE
    // sample (13 locked pref reads plus two GestureConfig allocations per sample, to read
    // one float). They are now snapshotted on first use and invalidated by an
    // OnSharedPreferenceChangeListener, so Gesture Lab tuning still takes effect without an
    // IME restart while a whole stroke costs no pref reads at all. Access goes through
    // PrefsManager, the project's single prefs accessor.

    private var cachedConfig: GestureConfig? = null
    private var cachedAssignments: GestureAssignments? = null
    private var cachedGesturesEnabled: Boolean? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        cachedConfig = null
        cachedAssignments = null
        cachedGesturesEnabled = null
    }
    private var prefsListenerRegistered = false

    /**
     * The shared prefs, registering the invalidation listener on first use. Deferred rather than
     * registered in an initializer because this bridge is constructed during BlackBerryIME's
     * field initialization, before `PrefsManager.init()` runs in `onCreate`.
     */
    private fun prefs(): SharedPreferences {
        val p = PrefsManager.getPrefs(ime)
        if (!prefsListenerRegistered) {
            p.registerOnSharedPreferenceChangeListener(prefsListener)
            prefsListenerRegistered = true
        }
        return p
    }

    /** Drop the preference listener and cached snapshots; called from the IME's teardown. */
    fun release() {
        if (prefsListenerRegistered) {
            PrefsManager.getPrefs(ime).unregisterOnSharedPreferenceChangeListener(prefsListener)
            prefsListenerRegistered = false
        }
        cachedConfig = null
        cachedAssignments = null
        cachedGesturesEnabled = null
    }

    /** Snapshot of the mode state the policy needs, from settings + runtime. */
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

    /** Current user gesture-slot assignments from prefs. */
    private fun gestureAssignments(): GestureAssignments =
        cachedAssignments ?: GestureAssignments.fromPrefs(prefs()).also { cachedAssignments = it }

    /** Classifier config, sourced from prefs (tuned in the Gesture Lab), defaulting to the tuned defaults. */
    private fun gestureConfig(): GestureConfig =
        cachedConfig ?: GestureConfig.fromPrefs(prefs()).also { cachedConfig = it }

    // ------------------------------------------------------------------ actions

    /** Map a resolved action onto existing IME calls. [trace] provides positional context (suggestion slot for commit). */
    private fun executeGestureAction(action: GestureAction, trace: GestureTrace) {
        if (InputPathDebug.perGesture()) Logger.info(TAG, "execute ${action.key}")
        when (action) {
            GestureAction.NONE -> {}
            GestureAction.COMMIT_SUGGESTION -> commitSuggestionAt(trace.start.x)
            GestureAction.DELETE_WORD -> deletePreviousWord()
            // Toggle the arrow-bar cursor mode — the same control the original double-tap used.
            GestureAction.ENTER_CURSOR_MODE -> ime.toggleCursorMode()
            GestureAction.NEXT_LANGUAGE -> ime.updateSuggestionsFromSubtype(InputSource.SOFTWARE)
            GestureAction.CYCLE_SYMBOLS -> {
                ime.getPhysicalKeyboardStateTracker().resetAltStateAndNotify()
                ime.getKeyboardSwitcher().onSymbolShiftToggle(ime.getCurrentInputType(), ime.getCurrentImeOptions(), true, true)
            }
            GestureAction.DISMISS_KEYBOARD -> ime.dismissKeyboard()
            GestureAction.EMOJI, GestureAction.UNDO ->
                Logger.warn(TAG, "action ${action.key} not yet implemented")
        }
    }

    /** Commit the suggestion under the flick's normalized X (0..1 across the strip's 3 slots). */
    private fun commitSuggestionAt(normalizedXraw: Float): Boolean {
        // Commit from the gesture-start snapshot (what was displayed), not the live suggestions,
        // which this gesture's own flow-feed may have cleared by now.
        val suggestions = startSuggestions ?: ime.getInputLogic().mCurrentSuggestions
        if (suggestions.isEmpty() || suggestions.size() == 0) {
            if (InputPathDebug.perGesture()) Logger.info(TAG, "commit_suggestion NO-OP: empty suggestions (snapshotWasNull=${startSuggestions == null})")
            return false
        }
        var normalizedX = normalizedXraw.coerceIn(0f, 1f)
        if (ime.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) normalizedX = 1f - normalizedX
        val visibleCount = minOf(suggestions.size(), STRIP_SLOT_COUNT)
        var slot = (STRIP_SLOT_COUNT * normalizedX).toInt()
        slot = if (visibleCount == 1) STRIP_SLOT_COUNT / 2 else slot.coerceIn(0, visibleCount - 1)
        val wordInfo = suggestions.getWordInfo(slot)
        if (wordInfo == null) {
            if (InputPathDebug.perGesture()) Logger.info(TAG, "commit_suggestion NO-OP: null wordInfo slot=$slot size=${suggestions.size()}")
            return false
        }
        if (InputPathDebug.perGesture()) Logger.info(TAG, "commit_suggestion OK: slot=$slot of ${suggestions.size()}")
        ime.onSuggestionPicked(wordInfo, InputSource.SOFTWARE)
        if (ime.getSettingsManager().getSettingsValues().flickCommitAnimationEnabled) {
            ime.auxBarManager?.getAuxBarView()?.getSuggestionView()?.flashCommittedSlot(slot)
        }
        return true
    }

    /** Delete the whitespace + word immediately before the cursor. */
    private fun deletePreviousWord() {
        val ric = ime.getInputLogic().mRichInputConnection
        val before = ric.getTextBeforeCursor(48, 0) ?: return
        if (before.isEmpty()) return
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        val toDelete = before.length - i
        if (toDelete > 0) ric.deleteSurroundingText(toDelete, 0)
    }
}
