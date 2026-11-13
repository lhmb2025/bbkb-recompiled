package dev.bbkb.ime.core.gesture.arbiter

/** Input mode the policy needs, built by the IME per contact so the policy stays pure. */
data class ModeState(
    val gesturesEnabled: Boolean,
    val swipeTypingEnabled: Boolean,
    /** Cursor (FCC) mode is open: drags move the cursor. */
    val fccActive: Boolean,
    val composing: Boolean,
    /** See [KeyTypingGuard]. */
    val keyTiming: KeyTiming = KeyTiming.CLEAR,
)

sealed interface PolicyOutcome {
    /** Don't consume: the key/flow pipeline handles the events. */
    data object Yield : PolicyOutcome

    data class Act(val action: GestureAction) : PolicyOutcome

    val label: String
        get() = when (this) {
            Yield -> "Yield"
            is Act -> "Act(${action.key})"
        }
}

/**
 * Pure `(classification, mode, assignments) → outcome`:
 *  - Tap, FlowTrace → Yield (the physical key path or the flow pipeline handles them).
 *  - FCC mode, gestures disabled, or inside a typing window ([KeyTiming.suppressesGestures]) → Yield.
 *  - Up (flick or swipe) → reserved Commit suggestion; down → the swipe-down slot;
 *    left/right split flick (fast) vs swipe (slow).
 */
class GesturePolicy {

    fun resolve(classification: GestureClassification, mode: ModeState, a: GestureAssignments): PolicyOutcome {
        when (classification) {
            is GestureClassification.Tap -> return PolicyOutcome.Yield
            is GestureClassification.FlowTrace -> return PolicyOutcome.Yield
            else -> {}
        }

        if (mode.fccActive) return PolicyOutcome.Yield
        if (!mode.gesturesEnabled) return PolicyOutcome.Yield
        if (mode.keyTiming.suppressesGestures) return PolicyOutcome.Yield

        val action = when (classification) {
            is GestureClassification.Hold -> a.hold
            is GestureClassification.DoubleTap -> a.doubleTap
            is GestureClassification.Swipe -> when (classification.direction) {
                GestureDirection.UP -> GestureAction.COMMIT_SUGGESTION
                GestureDirection.DOWN -> a.swipeDown
                GestureDirection.LEFT -> if (classification.fast) a.flickLeft else a.swipeLeft
                GestureDirection.RIGHT -> if (classification.fast) a.flickRight else a.swipeRight
            }
            else -> GestureAction.NONE
        }
        // No action must not consume: that aborts the in-progress flow word (touchCancel), so a
        // misclassified gesture-typing stroke would vanish instead of resolving to a word.
        return if (action == GestureAction.NONE) PolicyOutcome.Yield else PolicyOutcome.Act(action)
    }
}
