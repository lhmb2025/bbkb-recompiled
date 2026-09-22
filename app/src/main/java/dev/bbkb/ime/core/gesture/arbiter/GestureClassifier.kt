package dev.bbkb.ime.core.gesture.arbiter

import kotlin.math.min

/**
 * Stateless, pure classifier for completed gesture traces.
 *
 * Decision order (first match wins), designed so that **every contact resolves to exactly
 * one outcome and ambiguity falls back to the safest one (Tap)** — there are no silent
 * dead zones:
 *
 *  1. Stationary contact (moved ≤ tapSlop):
 *       - held long enough → [GestureClassification.Hold]
 *       - matches the previous tap in time+place → [GestureClassification.DoubleTap]
 *       - otherwise → [GestureClassification.Tap]
 *  2. Moving contact:
 *       - low straightness + enough arc length → [GestureClassification.FlowTrace]
 *       - straight enough + far enough → [GestureClassification.Swipe] (direction by nearest
 *         45° sector; "fast" if quick)
 *       - otherwise → [GestureClassification.Tap]  (ambiguous → safe fallback)
 *
 * Direction is chosen by [GestureDirection.fromAngle] so any straight movement maps to
 * *some* direction — maximizing tolerance for imprecise angles.
 */
class GestureClassifier(private val config: GestureConfig = GestureConfig()) {

    /**
     * @param trace the completed contact
     * @param previousTap the immediately preceding completed trace (for double-tap detection),
     *                    or null. Only its end point/time and stationarity matter.
     */
    fun classify(trace: GestureTrace, previousTap: GestureTrace? = null): GestureClassification {
        // Vertical-dominant strokes get scaled-down distance thresholds (see
        // GestureConfig.vDistanceScale): the pad is far wider than tall, and normalization
        // is by the wide axis, so vertical flicks are physically short. For degenerate or
        // near-zero traces the dominant angle is noise, but the scale then never matters:
        // their excursion is tiny on both axes and they classify as stationary either way.
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

        // Moving gesture. Decide flow-trace vs. swipe by straightness; never leave a dead zone.
        if (trace.straightness < config.flowStraightnessMax && trace.arcLength >= config.flowMinArcLength) {
            return GestureClassification.FlowTrace
        }

        // Distance gate, with flick rescue: a short stroke still qualifies if it was fast.
        val farEnough = trace.displacement >= config.minSwipeDistance * dScale ||
            (trace.displacement >= config.flickMinDistance * dScale && trace.meanSpeed >= config.flickRescueMinSpeed)
        if (farEnough && trace.straightness >= config.swipeStraightnessMin) {
            // A straight stroke is an action swipe only if it was lifted in motion (ballistic).
            // A flow-typed word — even a long, fast, dead-straight one like "ah" (A→H across the
            // row) — arrives at and settles on its final key, so its terminal speed is low; those
            // fall back to flow regardless of length or speed. Mis-typing a letter is far cheaper
            // to undo than a wrong commit/delete, so ambiguity resolves to flow.
            val ballistic = trace.terminalSpeed(config.terminalWindowMs) >= config.flowTerminalSpeedMax
            // Velocity breakpoints differ by axis: horizontal must clear a higher bar to protect
            // horizontal flow ("ah"); vertical rarely collides with flow and can fire eagerly.
            val swipeMin = if (vertical) config.vSwipeMinVelocity else config.hSwipeMinVelocity
            val flickMin = if (vertical) config.vFlickMinVelocity else config.hFlickMinVelocity
            val fastEnough = trace.meanSpeed >= swipeMin
            if (!fastEnough || !ballistic) {
                return GestureClassification.FlowTrace
            }
            val fast = trace.meanSpeed >= flickMin
            return GestureClassification.Swipe(direction, fast, confidence(trace))
        }

        // Moved a little, but neither a clean swipe nor a real trace → treat as a tap.
        return GestureClassification.Tap
    }

    private fun isDoubleTap(trace: GestureTrace, previousTap: GestureTrace?): Boolean {
        if (previousTap == null) return false
        if (previousTap.maxExcursion > config.tapSlop) return false
        val gap = trace.start.t - previousTap.end.t
        if (gap < config.doubleTapMinGapMs || gap > config.doubleTapMaxGapMs) return false
        return GestureTrace.distanceBetween(previousTap.end, trace.start) <= config.doubleTapSlop
    }

    /**
     * Confidence in [0,1] combining three independent margins:
     *  - straightness (how clean the line is),
     *  - angular decisiveness (distance from the 45° sector boundary; dead-on = best),
     *  - distance margin over the minimum swipe threshold (capped).
     * The policy layer can require higher confidence for destructive actions.
     */
    private fun confidence(trace: GestureTrace): Float {
        val straightnessScore = trace.straightness.coerceIn(0f, 1f)

        // Margin from a sector boundary is 0..45°; normalize so dead-on→1, on-boundary→0.
        val angularMargin = GestureDirection.marginFromSectorCenter(trace.dominantAngleDeg) // 0..45
        val angularScore = (1f - (angularMargin / 45f)).coerceIn(0f, 1f)

        val distanceScore = min(1f, trace.displacement / (config.minSwipeDistance * 2f))

        return (straightnessScore * 0.5f + angularScore * 0.3f + distanceScore * 0.2f).coerceIn(0f, 1f)
    }
}
