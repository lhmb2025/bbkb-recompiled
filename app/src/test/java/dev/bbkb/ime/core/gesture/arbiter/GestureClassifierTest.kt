package dev.bbkb.ime.core.gesture.arbiter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Synthetic, labeled corpus for [GestureClassifier]. These traces double as the baseline
 * for tuning [GestureConfig]: when adjusting thresholds, this corpus is the regression net.
 */
class GestureClassifierTest {

    private val classifier = GestureClassifier()

    // --- trace builders (normalized, y-down) ---

    private fun straight(x0: Float, y0: Float, x1: Float, y1: Float, durationMs: Long, n: Int = 12): GestureTrace {
        val pts = (0 until n).map { i ->
            val f = i / (n - 1f)
            TracePoint(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, (durationMs * f).toLong())
        }
        return GestureTrace(pts)
    }

    private fun stationary(x: Float, y: Float, startT: Long, durationMs: Long): GestureTrace =
        GestureTrace(listOf(TracePoint(x, y, startT), TracePoint(x, y, startT + durationMs)))

    /**
     * A straight trace that decelerates toward the end (ease-out in space, linear in time):
     * mimics swipe-typing that arrives at and settles on the target key. Terminal speed is low.
     */
    private fun decelStraight(x0: Float, y0: Float, x1: Float, y1: Float, durationMs: Long, n: Int = 12): GestureTrace {
        val pts = (0 until n).map { i ->
            val ft = i / (n - 1f)                       // linear in time
            val fs = 1f - (1f - ft) * (1f - ft)         // ease-out in space → decelerates
            TracePoint(x0 + (x1 - x0) * fs, y0 + (y1 - y0) * fs, (durationMs * ft).toLong())
        }
        return GestureTrace(pts)
    }

    /**
     * A straight move (possibly quick) that ends with a brief dwell on the target key — the finger
     * settles on the final letter before lifting. Terminal speed ≈ 0 regardless of stroke length.
     */
    private fun settledStraight(x0: Float, y0: Float, x1: Float, y1: Float, moveMs: Long, dwellMs: Long = 60, n: Int = 12): GestureTrace {
        val pts = ArrayList<TracePoint>()
        for (i in 0 until n) {
            val f = i / (n - 1f)
            pts.add(TracePoint(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, (moveMs * f).toLong()))
        }
        pts.add(TracePoint(x1, y1, moveMs + dwellMs)) // dwell on the target key
        return GestureTrace(pts)
    }

    /** A downward-bulging semicircle: high arc length, low straightness (a flow-typing-like trace). */
    private fun semicircle(durationMs: Long, n: Int = 24): GestureTrace {
        val cx = 0.5f; val cy = 0.5f; val r = 0.3f
        val pts = (0 until n).map { i ->
            val f = i / (n - 1f)
            val theta = Math.PI * (1.0 - f) // π → 0, passing through π/2 (downward)
            TracePoint((cx + r * cos(theta)).toFloat(), (cy + r * sin(theta)).toFloat(), (durationMs * f).toLong())
        }
        return GestureTrace(pts)
    }

    // --- swipes / flicks ---

    @Test fun fastStraightUp_isUpFlick() {
        val c = classifier.classify(straight(0.5f, 0.8f, 0.5f, 0.2f, durationMs = 120))
        assertTrue(c is GestureClassification.Swipe)
        c as GestureClassification.Swipe
        assertEquals(GestureDirection.UP, c.direction)
        assertTrue("expected fast flick", c.fast)
        assertTrue("expected high confidence", c.confidence > 0.8f)
    }

    @Test fun slowLongUp_isUpGlide() {
        val c = classifier.classify(straight(0.5f, 0.85f, 0.5f, 0.2f, durationMs = 450))
        assertTrue(c is GestureClassification.Swipe)
        c as GestureClassification.Swipe
        assertEquals(GestureDirection.UP, c.direction)
        assertTrue("expected non-fast glide", !c.fast)
    }

    @Test fun straightDown_isDown() {
        val c = classifier.classify(straight(0.5f, 0.2f, 0.5f, 0.8f, durationMs = 150))
        assertEquals(GestureDirection.DOWN, (c as GestureClassification.Swipe).direction)
    }

    @Test fun straightLeft_isLeft() {
        val c = classifier.classify(straight(0.8f, 0.5f, 0.2f, 0.5f, durationMs = 150))
        assertEquals(GestureDirection.LEFT, (c as GestureClassification.Swipe).direction)
    }

    @Test fun straightRight_isRight() {
        val c = classifier.classify(straight(0.2f, 0.5f, 0.8f, 0.5f, durationMs = 150))
        assertEquals(GestureDirection.RIGHT, (c as GestureClassification.Swipe).direction)
    }

    @Test fun diagonalNearBoundary_stillResolvesToASwipe() {
        // Exactly on the up/right 45° boundary — must resolve deterministically, never be rejected.
        val c = classifier.classify(straight(0.3f, 0.7f, 0.7f, 0.3f, durationMs = 150))
        assertTrue("boundary diagonal must not vanish into a dead zone", c is GestureClassification.Swipe)
    }

    // --- taps / holds / double-taps ---

    // --- flick rescue: fast-short strokes are swipes, slow-short ones stay taps ---
    // Values from on-device capture (KEY2, 2026-08-09): real commit flicks measured
    // disp 0.077-0.089 at mean speed 1.8-3.6 and were dying as Taps under the plain
    // minSwipeDistance gate.

    @Test fun fastShortUpFlick_isRescuedAsSwipe() {
        // disp ~0.085 (< minSwipeDistance 0.12), 30ms -> meanSpeed ~2.8 (> rescue 1.2)
        val c = classifier.classify(straight(0.5f, 0.4f, 0.5f, 0.315f, durationMs = 30))
        assertTrue("expected Swipe, got $c", c is GestureClassification.Swipe)
        assertEquals(GestureDirection.UP, (c as GestureClassification.Swipe).direction)
    }

    @Test fun slowShortDrift_isNeverAnActionSwipe() {
        // Same distance but 300ms -> meanSpeed ~0.28: with vertical scaling this clears the
        // distance gate, but it is not ballistic (terminal ~0.28 < 0.6), so it resolves to
        // flow -- the designed safe fallback. The invariant that matters: never a Swipe.
        val c = classifier.classify(straight(0.5f, 0.4f, 0.5f, 0.315f, durationMs = 300))
        assertTrue("expected non-Swipe, got $c", c !is GestureClassification.Swipe)
    }

    @Test fun fastButTinyHorizontalJitter_staysTap() {
        // Horizontal disp ~0.05 is under flickMinDistance (0.07, unscaled on the wide
        // axis) -- speed alone must not rescue it
        val c = classifier.classify(straight(0.5f, 0.4f, 0.45f, 0.4f, durationMs = 20))
        assertEquals(GestureClassification.Tap, c)
    }

    // --- vertical scaling: the pad is ~2.3x wider than tall, so vertical thresholds are
    // scaled by vDistanceScale (0.5). Values from the second on-device capture
    // (2026-08-09 evening): a real flick at disp 0.053 / speed 1.26 was still dying --
    // not even reaching the distance gate, because maxExcursion <= tapSlop (0.06)
    // classified it as stationary first.

    @Test fun veryShortFastVerticalFlick_isSwipe() {
        // disp ~0.053, 42ms -> meanSpeed ~1.26: the captured failing stroke, verbatim
        val c = classifier.classify(straight(0.5f, 0.4f, 0.5f, 0.347f, durationMs = 42))
        assertTrue("expected Swipe, got $c", c is GestureClassification.Swipe)
        assertEquals(GestureDirection.UP, (c as GestureClassification.Swipe).direction)
    }

    @Test fun veryShortSlowVerticalDrift_staysStationary() {
        // Same distance, 400ms -> meanSpeed ~0.13: finger wobble on a key, not a flick
        val c = classifier.classify(straight(0.5f, 0.4f, 0.5f, 0.347f, durationMs = 400))
        assertEquals(GestureClassification.Tap, c)
    }

    @Test fun subScaledSlopVerticalJitter_staysTap() {
        // disp ~0.025 is under even the scaled tapSlop (0.03) -- stationary, a tap
        val c = classifier.classify(straight(0.5f, 0.4f, 0.5f, 0.375f, durationMs = 20))
        assertEquals(GestureClassification.Tap, c)
    }

    @Test fun stationaryShort_isTap() {
        assertEquals(GestureClassification.Tap, classifier.classify(stationary(0.5f, 0.5f, 0, 60)))
    }

    @Test fun stationaryHeld_isHold() {
        assertEquals(GestureClassification.Hold, classifier.classify(stationary(0.5f, 0.5f, 0, 320)))
    }

    @Test fun twoTapsSameSpot_isDoubleTap() {
        val first = stationary(0.5f, 0.5f, 0, 60)
        val second = stationary(0.5f, 0.5f, 200, 60) // gap 140ms, same spot
        assertEquals(GestureClassification.DoubleTap, classifier.classify(second, previousTap = first))
    }

    @Test fun twoTapsTooFarApartInTime_isNotDoubleTap() {
        val first = stationary(0.5f, 0.5f, 0, 60)
        val second = stationary(0.5f, 0.5f, 800, 60) // gap way over window
        assertEquals(GestureClassification.Tap, classifier.classify(second, previousTap = first))
    }

    @Test fun twoTapsDifferentSpots_isNotDoubleTap() {
        val first = stationary(0.2f, 0.5f, 0, 60)
        val second = stationary(0.8f, 0.5f, 200, 60) // far apart in space
        assertEquals(GestureClassification.Tap, classifier.classify(second, previousTap = first))
    }

    // --- flow trace ---

    @Test fun meanderingArc_isFlowTrace() {
        assertEquals(GestureClassification.FlowTrace, classifier.classify(semicircle(durationMs = 500)))
    }

    // --- terminal-dwell: short swipe-typed words settle on a key (low terminal speed) and
    //     must NOT read as action swipes; ballistic flicks of the same length still do. ---

    @Test fun shortDeceleratingHorizontal_isFlowTrace_twoLetterWord() {
        // "we": short straight horizontal that settles on the target key → flow, not delete.
        val c = classifier.classify(decelStraight(0.45f, 0.5f, 0.65f, 0.5f, durationMs = 240))
        assertEquals(GestureClassification.FlowTrace, c)
    }

    @Test fun shortDeceleratingVertical_isFlowTrace_notCommitSwipe() {
        // "by": short vertical trace that settles → flow, not a commit flick.
        val c = classifier.classify(decelStraight(0.5f, 0.4f, 0.5f, 0.62f, durationMs = 240))
        assertEquals(GestureClassification.FlowTrace, c)
    }

    @Test fun shortBallisticUp_staysSwipe_commitFlick() {
        // Same short displacement but lifted in motion (constant velocity) → an action flick.
        val c = classifier.classify(straight(0.5f, 0.6f, 0.5f, 0.35f, durationMs = 120))
        assertTrue("short ballistic flick must stay a swipe", c is GestureClassification.Swipe)
        assertEquals(GestureDirection.UP, (c as GestureClassification.Swipe).direction)
    }

    @Test fun longStraightHorizontalThatSettles_isFlowTrace_ahWord() {
        // "ah": A→H spans most of the row, dead straight and fairly quick, but settles on H →
        // flow, not a left swipe. There is no max-displacement cap that would force it to a swipe.
        val c = classifier.classify(settledStraight(0.10f, 0.5f, 0.90f, 0.5f, moveMs = 200))
        assertEquals(GestureClassification.FlowTrace, c)
    }

    @Test fun longBallisticHorizontal_isSwipe_deleteWord() {
        // A fast left throw lifted in motion (no dwell) → an action swipe (delete word).
        val c = classifier.classify(straight(0.85f, 0.5f, 0.15f, 0.5f, durationMs = 120))
        assertTrue("ballistic horizontal throw is a swipe", c is GestureClassification.Swipe)
        assertEquals(GestureDirection.LEFT, (c as GestureClassification.Swipe).direction)
    }

    // --- ambiguity falls back safely ---

    @Test fun shortMoveBelowSwipeThreshold_fallsBackToTap() {
        // Moves more than tapSlop (0.06) but less than minSwipeDistance (0.12), straight,
        // and too slow for the flick rescue. Horizontal: the wide axis keeps unscaled
        // thresholds (the equivalent vertical stroke is now a legitimate flick).
        val c = classifier.classify(straight(0.5f, 0.5f, 0.42f, 0.5f, durationMs = 220))
        assertEquals(GestureClassification.Tap, c)
    }

    // --- no dead zone: every straight long gesture resolves to a swipe, at every angle ---

    @Test fun noDeadZone_allAnglesClassifyAsSwipe() {
        var angle = 0
        while (angle < 360) {
            val rad = Math.toRadians(angle.toDouble())
            val x1 = (0.5f + 0.35f * cos(rad)).toFloat()
            val y1 = (0.5f + 0.35f * sin(rad)).toFloat()
            val c = classifier.classify(straight(0.5f, 0.5f, x1, y1, durationMs = 150))
            assertTrue("angle $angle should be a swipe, was ${c.label}", c is GestureClassification.Swipe)
            angle += 15
        }
    }
}
