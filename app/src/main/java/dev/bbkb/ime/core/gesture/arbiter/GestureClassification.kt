package dev.bbkb.ime.core.gesture.arbiter

/** Outcome of classifying one [GestureTrace]; [GesturePolicy] maps it to an action. */
sealed class GestureClassification {
    /** A normal key press; the fallback for ambiguous input. */
    object Tap : GestureClassification()

    /** Held past [GestureConfig.holdMinDurationMs]. */
    object Hold : GestureClassification()

    object DoubleTap : GestureClassification()

    /** Yields to the type-by-swiping pipeline. */
    object FlowTrace : GestureClassification()

    /**
     * @param fast a flick (above the axis's flick velocity) rather than a swipe
     * @param confidence 0..1
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

/** Illustrative mapping for the Gesture Lab readout; not the production policy ([GesturePolicy]). */
object ActionPreview {
    fun describe(classification: GestureClassification): String = when (classification) {
        GestureClassification.Tap -> "Key press (physical key) — tap-to-type deferred"
        GestureClassification.Hold -> "Enter cursor mode / FCC (assignable, default)"
        GestureClassification.DoubleTap -> "Unassigned (assignable, default: no action)"
        GestureClassification.FlowTrace -> "Type by swiping (yield to flow pipeline)"
        is GestureClassification.Swipe -> when (classification.direction) {
            GestureDirection.UP -> if (classification.fast) "Commit highlighted suggestion (reserved)" else "Swipe up (assignable)"
            GestureDirection.DOWN -> "Cycle symbols (assignable, default)"
            GestureDirection.LEFT -> "Delete word — destructive, needs high confidence"
            GestureDirection.RIGHT -> "Accept / restore"
        }
    }
}
