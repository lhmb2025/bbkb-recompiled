package dev.bbkb.ime.core.gesture.arbiter

import kotlin.math.min

/**
 * Pure classifier for completed traces. Every contact resolves to one outcome, no dead zones
 * (first match wins):
 *
 *  1. Stationary contact (moved ≤ tapSlop):
 *       - held long enough → [GestureClassification.Hold]
 *       - matches the previous tap in time+place → [GestureClassification.DoubleTap]
 *       - otherwise → [GestureClassification.Tap]
 *  2. Moving contact:
 *       - low straightness + enough arc length → [GestureClassification.FlowTrace]
 *       - straight + far enough + ballistic → [GestureClassification.Swipe] (nearest 45° sector)
 *       - straight but settled or slow → [GestureClassification.FlowTrace]
 *       - otherwise → [GestureClassification.Tap]
 */
class GestureClassifier(private val config: GestureConfig = GestureConfig()) {

    /**
     * @param trace the completed contact
     * @param previousTap the preceding completed trace, for double-tap detection
     */
    fun classify(trace: GestureTrace, previousTap: GestureTrace? = null): GestureClassification {
        // Vertical strokes get scaled thresholds (GestureConfig.vDistanceScale). For near-zero
        // traces the angle is noise, but they are stationary either way.
        val direction = GestureDirection.fromAngle(trace.dominantAngleDeg)
        val vertical = direction == GestureDirection.UP || direction == GestureDirection.DOWN
        val dScale = if (vertical) config.vDistanceScale else 1f

        val stationary = trace.isDegenerate || trace.maxExcursion <= config.tapSlop * dScale

        if (stationary) {
            if (trace.durationMs >= config.holdMinDurationMs) {
                return GestureClassification.Hold
            }
            if (isDoubleTap(trace, previousTap)) {
                return GestureClassification.DoubleTap
            }
            return GestureClassification.Tap
        }

        if (trace.straightness < config.flowStraightnessMax && trace.arcLength >= config.flowMinArcLength) {
            return GestureClassification.FlowTrace
        }

        // Flick rescue: a short stroke still qualifies if it was fast.
        val farEnough = trace.displacement >= config.minSwipeDistance * dScale ||
            (trace.displacement >= config.flickMinDistance * dScale && trace.meanSpeed >= config.flickRescueMinSpeed)
        if (farEnough && trace.straightness >= config.swipeStraightnessMin) {
            // Only a stroke lifted in motion is an action; a flow word settles on its last key.
            // Ambiguity resolves to flow: a wrong letter is cheaper to undo than a wrong action.
            val ballistic = trace.terminalSpeed(config.terminalWindowMs) >= config.flowTerminalSpeedMax
            val swipeMin = if (vertical) config.vSwipeMinVelocity else config.hSwipeMinVelocity
            val flickMin = if (vertical) config.vFlickMinVelocity else config.hFlickMinVelocity
            val fastEnough = trace.meanSpeed >= swipeMin
            if (!fastEnough || !ballistic) {
                return GestureClassification.FlowTrace
            }
            val fast = trace.meanSpeed >= flickMin
            return GestureClassification.Swipe(direction, fast, confidence(trace))
        }

        return GestureClassification.Tap
    }

    private fun isDoubleTap(trace: GestureTrace, previousTap: GestureTrace?): Boolean {
        if (previousTap == null) return false
        if (previousTap.maxExcursion > config.tapSlop) return false
        val gap = trace.start.t - previousTap.end.t
        if (gap < config.doubleTapMinGapMs || gap > config.doubleTapMaxGapMs) return false
        return GestureTrace.distanceBetween(previousTap.end, trace.start) <= config.doubleTapSlop
    }

    /** [0,1] from straightness, angular margin from the sector boundary, and distance margin (capped). */
    private fun confidence(trace: GestureTrace): Float {
        val straightnessScore = trace.straightness.coerceIn(0f, 1f)

        val angularMargin = GestureDirection.marginFromSectorCenter(trace.dominantAngleDeg) // 0..45
        val angularScore = (1f - (angularMargin / 45f)).coerceIn(0f, 1f)

        val distanceScore = min(1f, trace.displacement / (config.minSwipeDistance * 2f))

        return (straightnessScore * 0.5f + angularScore * 0.3f + distanceScore * 0.2f).coerceIn(0f, 1f)
    }
}
