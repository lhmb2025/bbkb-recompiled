package dev.bbkb.ime.core.gesture.arbiter

/**
 * The current input mode the policy needs. Built by the IME each contact from settings + runtime
 * state; kept tiny and explicit so the policy stays a pure function of its inputs.
 */
data class ModeState(
    /** Master toggle for assignable CKB gestures. */
    val gesturesEnabled: Boolean,
    /** Type-by-swiping (flow) enabled — flow traces yield to the Nuance pipeline. */
    val swipeTypingEnabled: Boolean,
    /** Cursor (FCC) mode is currently open — drags move the cursor, not fire gestures. */
    val fccActive: Boolean,
    /** Editor is composing a word (e.g. relevant to commit). */
    val composing: Boolean,
    /** Where this contact sits relative to the hardware keys being typed — see [KeyTypingGuard]. */
    val keyTiming: KeyTiming = KeyTiming.CLEAR,
)

/** What the policy decided for one classified gesture. */
sealed interface PolicyOutcome {
    /** Don't consume — hand the events to the existing key/flow pipeline. */
    data object Yield : PolicyOutcome

    /** Perform [action] and consume the gesture. */
    data class Act(val action: GestureAction) : PolicyOutcome

    val label: String
        get() = when (this) {
            Yield -> "Yield"
            is Act -> "Act(${action.key})"
        }
}

/**
 * Pure mapping of `(classification, mode, assignments) → outcome`. No Android dependencies, fully
 * unit-testable. Replaces the scattered guard predicates of the legacy gesture path.
 *
 * Rules:
 *  - [GestureClassification.Tap] → Yield (tap-to-type deferred; the physical key path types).
 *  - [GestureClassification.FlowTrace] → Yield (Nuance flow pipeline, or keys if swipe typing off).
 *  - In cursor (FCC) mode, or with gestures disabled → Yield.
 *  - Inside any of the typing windows ([KeyTiming.suppressesGestures]) → Yield: a finger that
 *    lands, rests or moves while keys are going down is typing, and the original never
 *    dispatched a gesture there either (its 150 ms noise and 350 ms suppression windows).
 *  - Flick **up** → reserved Commit suggestion. Down (flick or swipe) → the swipe-down slot.
 *    Left/right distinguish flick (fast) vs swipe (slow); slow up uses the swipe-up slot.
 */
class GesturePolicy {

    fun resolve(c: GestureClassification, mode: ModeState, a: GestureAssignments): PolicyOutcome {
        when (c) {
            is GestureClassification.Tap -> return PolicyOutcome.Yield
            is GestureClassification.FlowTrace -> return PolicyOutcome.Yield
            else -> {}
        }

        if (mode.fccActive) return PolicyOutcome.Yield
        if (!mode.gesturesEnabled) return PolicyOutcome.Yield
        if (mode.keyTiming.suppressesGestures) return PolicyOutcome.Yield

        val action = when (c) {
            is GestureClassification.Hold -> a.hold
            is GestureClassification.DoubleTap -> a.doubleTap
            is GestureClassification.Swipe -> when (c.direction) {
                GestureDirection.UP -> GestureAction.COMMIT_SUGGESTION // reserved; flick & swipe up both commit
                GestureDirection.DOWN -> a.swipeDown // flick-down folds into the swipe-down slot
                GestureDirection.LEFT -> if (c.fast) a.flickLeft else a.swipeLeft
                GestureDirection.RIGHT -> if (c.fast) a.flickRight else a.swipeRight
            }
            // Tap/FlowTrace already returned above.
            else -> GestureAction.NONE
        }
        // A gesture with no assigned action must NOT consume the stroke: consuming aborts the
        // in-progress flow word (touchCancel), so a real gesture-typing stroke misclassified as a
        // no-action swipe would silently vanish. Yield instead so it still resolves to a word.
        return if (action == GestureAction.NONE) PolicyOutcome.Yield else PolicyOutcome.Act(action)
    }
}
