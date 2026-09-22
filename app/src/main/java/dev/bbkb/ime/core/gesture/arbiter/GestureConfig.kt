package dev.bbkb.ime.core.gesture.arbiter

/**
 * Tunables for [GestureClassifier]. All distances are in **normalized** units
 * (fraction of the capture surface's reference dimension — see [TracePoint]).
 *
 * Defaults are starting points for the Gesture Lab; tune them against the test
 * corpus and the Lab readout. The philosophy: maximize tolerance for sloppy input
 * on benign actions, while letting the policy layer demand higher [Swipe.confidence]
 * for destructive ones (delete word, dismiss keyboard).
 */
data class GestureConfig(
    /** Movement at or below this (normalized) is treated as stationary → tap / hold / double-tap. */
    val tapSlop: Float = 0.06f,

    /** A stationary contact held at least this long becomes a Hold (→ enter cursor/FCC mode). */
    val holdMinDurationMs: Long = 280L,

    /** Max gap between two taps to count as a double-tap. */
    val doubleTapMaxGapMs: Long = 300L,
    /** Min gap so a single bounce isn't read as two taps. */
    val doubleTapMinGapMs: Long = 40L,
    /** Max distance between the two taps' locations to count as a double-tap. */
    val doubleTapSlop: Float = 0.12f,

    /** Minimum straight-line displacement for a moving gesture to count as a swipe at all. */
    val minSwipeDistance: Float = 0.12f,

    /**
     * Flick rescue: a stroke shorter than [minSwipeDistance] still counts as a swipe if it
     * traveled at least this far AND at mean speed >= [flickRescueMinSpeed]. Real flicks are
     * fast and short -- on this pad (normalized isotropically by the ~1080-unit width, only
     * ~470 tall) a vertical flick physically cannot travel 0.12, and on-device capture showed
     * genuine commit flicks at disp 0.077-0.089 / speed 1.8-3.6 dying as Taps. Keep
     * [flickMinDistance] above tapSlop so sloppy taps stay taps.
     */
    val flickMinDistance: Float = 0.07f,
    /** Mean speed (normalized units/sec) required for the short-stroke flick rescue.
     *  0.65: the 56-sample labeled session had a real swipe-down at speed 0.69 die at the
     *  previous 0.8; the slowest labeled non-gesture drift measured 0.47. */
    val flickRescueMinSpeed: Float = 0.65f,

    /**
     * Distance-threshold scale for vertical-dominant strokes. Coordinates are normalized
     * isotropically by the pad's larger (X) range, but the KEY2 pad is ~1080x470, so a
     * vertical stroke physically cannot exceed ~0.44 and real vertical flicks measured
     * 0.045-0.089 on device. Applied to tapSlop, minSwipeDistance and flickMinDistance
     * when the stroke's dominant axis is vertical; horizontal thresholds are unchanged.
     */
    val vDistanceScale: Float = 0.5f,

    /** A moving gesture must be at least this straight to be a swipe (vs. a flow-typing trace). */
    val swipeStraightnessMin: Float = 0.82f,

    /** Below this straightness (and with enough arc length) a moving gesture is a flow-typing trace. */
    val flowStraightnessMax: Float = 0.80f,
    /** Minimum arc length before a low-straightness gesture is considered a flow trace. */
    val flowMinArcLength: Float = 0.20f,

    // --- velocity breakpoints (normalized units/sec, straight-line mean speed) ---
    // Separate breakpoints per axis, each a two-thumb range:
    //   flow  |swipeMinVelocity|  swipe  |flickMinVelocity|  flick
    // Horizontal gestures (L/R) collide with horizontal flow ("ah"), so they typically want a
    // higher bar; vertical gestures (commit-up / symbols-down) rarely collide with flow and can
    // fire more eagerly. The classifier picks the pair by the gesture's dominant axis.

    /** Horizontal lower breakpoint: a horizontal stroke slower than this falls back to flow. 0 disables. */
    val hSwipeMinVelocity: Float = 0f,
    /** Horizontal upper breakpoint: a horizontal swipe at/above this mean velocity is a flick. */
    val hFlickMinVelocity: Float = 4.5f,

    /** Vertical lower breakpoint: a vertical stroke slower than this falls back to flow. 0 disables. */
    val vSwipeMinVelocity: Float = 0f,
    /** Vertical upper breakpoint: a vertical swipe at/above this mean velocity is a flick. */
    val vFlickMinVelocity: Float = 2.5f,

    /** Window (before lift-off) over which terminal speed is measured. */
    val terminalWindowMs: Long = 50L,

    /**
     * Terminal-dwell threshold (normalized units/sec). A straight gesture whose terminal speed
     * is **below** this settled on its final key → flow-typed word; at or above it the finger
     * was still moving at release (ballistic) → an action swipe/flick.
     *
     * This is the sole geometric discriminator between flow and swipe for straight strokes, and
     * it is independent of length: a long, fast, dead-straight flow like "ah" (A→H across the
     * row) still settles on H, so it stays flow. (The genuinely hard case — a quick flow that
     * never dwells — needs key-endpoint topology / engine arbitration, handled in the IME
     * integration where the key grid is available.)
     */
    val flowTerminalSpeedMax: Float = 0.6f,
) {
    /** Persist the user-tunable fields (the ones the Gesture Lab exposes). */
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
        /** Load the tunable fields from prefs; anything unset falls back to the data-class default. */
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
