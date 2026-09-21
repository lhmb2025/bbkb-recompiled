package dev.bbkb.ime.core.gesture.arbiter

/**
 * The outcome of classifying one completed [GestureTrace].
 *
 * This is the typed output of the detection layer. The policy layer maps it (plus the
 * current mode) to an editor action — see [ActionPreview] for the Lab's illustrative
 * mapping. Note that flow-typing word recognition stays in the existing pipeline:
 * a [FlowTrace] verdict means "yield these events, don't consume them."
 */
sealed class GestureClassification {
    /** A stationary contact: a normal key press. The safe fallback for ambiguous input. */
    object Tap : GestureClassification()

    /** A stationary contact held past [GestureConfig.holdMinDurationMs] → enter cursor (FCC) mode. */
    object Hold : GestureClassification()

    /** Two stationary contacts in quick succession at the same spot. */
    object DoubleTap : GestureClassification()

    /** A meandering, multi-key trace → hand off to the type-by-swiping (flow) pipeline. */
    object FlowTrace : GestureClassification()

    /**
     * A straight directional gesture.
     * @param direction nearest cardinal direction
     * @param fast true if completed quickly (a "flick") vs. a deliberate "glide"
     * @param confidence 0..1, combining straightness, angular decisiveness and distance margin
     */
    data class Swipe(
        val direction: GestureDirection,
        val fast: Boolean,
        val confidence: Float,
    ) : GestureClassification()

    val label: String
        get() = when (this) {
            Tap -> "Tap"
            Hold -> "Hold"
            DoubleTap -> "Double-tap"
            FlowTrace -> "Flow trace"
            is Swipe -> "${if (fast) "Flick" else "Swipe"} ${direction.name.lowercase()} (${(confidence * 100).toInt()}%)"
        }
}

/**
 * Illustrative classification → action mapping for the Gesture Lab readout, so testers
 * can see "what would this do." This is NOT the production policy (that lives in the IME
 * and accounts for live mode/state); it mirrors the intended semantics, including the
 * consequence-tiering note for destructive actions.
 */
object ActionPreview {
    fun describe(c: GestureClassification): String = when (c) {
        GestureClassification.Tap -> "Key press (physical key) — tap-to-type deferred"
        GestureClassification.Hold -> "Enter cursor mode / FCC (assignable, default)"
        GestureClassification.DoubleTap -> "Unassigned (assignable, default: no action)"
        GestureClassification.FlowTrace -> "Type by swiping (yield to flow pipeline)"
        is GestureClassification.Swipe -> when (c.direction) {
            // Up flick is reserved (commit); slow up-swipe is assignable. Down (flick or swipe)
            // is one assignable slot, default "Cycle symbols". L/R flick+swipe are assignable.
            GestureDirection.UP -> if (c.fast) "Commit highlighted suggestion (reserved)" else "Swipe up (assignable)"
            GestureDirection.DOWN -> "Cycle symbols (assignable, default)"
            GestureDirection.LEFT -> "Delete word — destructive, needs high confidence"
            GestureDirection.RIGHT -> "Accept / restore"
        }
    }
}
