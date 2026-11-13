package dev.bbkb.ime.core.gesture.arbiter

/**
 * Tunables for [GestureClassifier]; distances are normalized (see [TracePoint]). Tolerant of
 * sloppy input; the policy layer demands higher [Swipe.confidence] for destructive actions.
 */
data class GestureConfig(
    /** Movement at or below this is stationary (tap, hold, double-tap). */
    val tapSlop: Float = 0.06f,

    /** A stationary contact held this long becomes a Hold. */
    val holdMinDurationMs: Long = 280L,

    val doubleTapMaxGapMs: Long = 300L,
    /** So a single bounce isn't read as two taps. */
    val doubleTapMinGapMs: Long = 40L,
    val doubleTapSlop: Float = 0.12f,

    val minSwipeDistance: Float = 0.12f,

    /**
     * Flick rescue: a stroke shorter than [minSwipeDistance] is still a swipe if it travels this
     * far at mean speed >= [flickRescueMinSpeed] (real flicks are short and fast). Keep it above
     * tapSlop so sloppy taps stay taps.
     */
    val flickMinDistance: Float = 0.07f,
    /** Normalized units/sec; the slowest labeled non-gesture drift measured 0.47. */
    val flickRescueMinSpeed: Float = 0.65f,

    /**
     * Scales tapSlop, minSwipeDistance and flickMinDistance for vertical-dominant strokes: the pad
     * (~1080x470) is normalized by its width, so a vertical stroke cannot exceed ~0.44.
     */
    val vDistanceScale: Float = 0.5f,

    /** Minimum straightness for a swipe (vs. a flow-typing trace). */
    val swipeStraightnessMin: Float = 0.82f,

    /** Below this straightness (with enough arc length) a gesture is a flow trace. */
    val flowStraightnessMax: Float = 0.80f,
    val flowMinArcLength: Float = 0.20f,

    // Velocity breakpoints per dominant axis (normalized units/sec, mean speed):
    //   flow  |swipeMinVelocity|  swipe  |flickMinVelocity|  flick
    // Horizontal strokes collide with horizontal flow ("ah"); vertical ones rarely do.

    /** Slower horizontal strokes fall back to flow; 0 disables. */
    val hSwipeMinVelocity: Float = 0f,
    val hFlickMinVelocity: Float = 4.5f,

    /** Slower vertical strokes fall back to flow; 0 disables. */
    val vSwipeMinVelocity: Float = 0f,
    val vFlickMinVelocity: Float = 2.5f,

    /** Window before lift-off for terminal speed. */
    val terminalWindowMs: Long = 50L,

    /**
     * The sole flow vs. swipe test for straight strokes, independent of length: terminal speed
     * below this settled on a key (flow, e.g. "ah"); at or above it was ballistic (action).
     * A quick flow that never dwells is left to key-grid arbitration in the IME.
     */
    val flowTerminalSpeedMax: Float = 0.6f,
) {
    /** Persists the fields the Gesture Lab exposes. */
    fun writeTo(prefs: android.content.SharedPreferences) {
        prefs.edit()
            .putFloat("ckb_cfg_tap_slop", tapSlop)
            .putInt("ckb_cfg_hold_ms", holdMinDurationMs.toInt())
            .putFloat("ckb_cfg_min_swipe_dist", minSwipeDistance)
            .putFloat("ckb_cfg_swipe_straight_min", swipeStraightnessMin)
            .putFloat("ckb_cfg_flow_straight_max", flowStraightnessMax)
            .putFloat("ckb_cfg_flow_term_spd_max", flowTerminalSpeedMax)
            .putFloat("ckb_cfg_h_swipe_min_vel", hSwipeMinVelocity)
            .putFloat("ckb_cfg_h_flick_min_vel", hFlickMinVelocity)
            .putFloat("ckb_cfg_v_swipe_min_vel", vSwipeMinVelocity)
            .putFloat("ckb_cfg_v_flick_min_vel", vFlickMinVelocity)
            .apply()
    }

    companion object {
        /** Unset fields fall back to the defaults. */
        fun fromPrefs(prefs: android.content.SharedPreferences): GestureConfig {
            val d = GestureConfig()
            return GestureConfig(
                tapSlop = prefs.getFloat("ckb_cfg_tap_slop", d.tapSlop),
                holdMinDurationMs = prefs.getInt("ckb_cfg_hold_ms", d.holdMinDurationMs.toInt()).toLong(),
                minSwipeDistance = prefs.getFloat("ckb_cfg_min_swipe_dist", d.minSwipeDistance),
                flickMinDistance = prefs.getFloat("ckb_cfg_flick_min_dist", d.flickMinDistance),
                flickRescueMinSpeed = prefs.getFloat("ckb_cfg_flick_rescue_spd", d.flickRescueMinSpeed),
                vDistanceScale = prefs.getFloat("ckb_cfg_v_dist_scale", d.vDistanceScale),
                swipeStraightnessMin = prefs.getFloat("ckb_cfg_swipe_straight_min", d.swipeStraightnessMin),
                flowStraightnessMax = prefs.getFloat("ckb_cfg_flow_straight_max", d.flowStraightnessMax),
                flowTerminalSpeedMax = prefs.getFloat("ckb_cfg_flow_term_spd_max", d.flowTerminalSpeedMax),
                hSwipeMinVelocity = prefs.getFloat("ckb_cfg_h_swipe_min_vel", d.hSwipeMinVelocity),
                hFlickMinVelocity = prefs.getFloat("ckb_cfg_h_flick_min_vel", d.hFlickMinVelocity),
                vSwipeMinVelocity = prefs.getFloat("ckb_cfg_v_swipe_min_vel", d.vSwipeMinVelocity),
                vFlickMinVelocity = prefs.getFloat("ckb_cfg_v_flick_min_vel", d.vFlickMinVelocity),
            )
        }
    }
}
