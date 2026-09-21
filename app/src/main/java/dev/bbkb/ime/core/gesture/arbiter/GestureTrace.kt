package dev.bbkb.ime.core.gesture.arbiter

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A single sampled point of a gesture trace.
 *
 * Coordinates are **normalized and isotropic**: both axes are divided by the same
 * reference dimension (the capture surface's reference size) so that distances and
 * angles are comparable regardless of the input source (on-screen Lab canvas vs.
 * the physical capacitive keypad). `y` grows downward, matching Android screen space.
 *
 * @param x normalized x (fraction of the reference dimension)
 * @param y normalized y (fraction of the reference dimension)
 * @param t event time in milliseconds
 */
data class TracePoint(val x: Float, val y: Float, val t: Long)

/**
 * An immutable recording of one finger contact (DOWN .. UP/CANCEL), plus the
 * whole-trace geometry the [GestureClassifier] decides from.
 *
 * The design deliberately keys off **whole-trace geometry** (displacement, arc length,
 * straightness, dominant angle, duration) rather than instantaneous end-of-gesture
 * velocity, which is noisy on a small capacitive surface.
 *
 * Audit GD-39: the derived-geometry delegates use [LazyThreadSafetyMode.NONE]. A trace is built
 * by [GestureTraceRecorder] and handed straight to its listener on the same (main) thread, and
 * [GestureClassifier] reads essentially all of them per gesture — the default SYNCHRONIZED mode
 * cost six monitor enter/exits per contact for values that are never touched off-thread.
 */
class GestureTrace(
    val points: List<TracePoint>,
    val pointerId: Int = 0,
) {
    /** A trace with fewer than two points carries no movement information. */
    val isDegenerate: Boolean get() = points.size < 2

    val start: TracePoint get() = points.first()
    val end: TracePoint get() = points.last()

    val durationMs: Long get() = if (points.size < 2) 0L else end.t - start.t

    /** Straight-line start→end distance, in normalized units. */
    val displacement: Float by lazy(LazyThreadSafetyMode.NONE) {
        if (points.size < 2) 0f else hypot((end.x - start.x).toDouble(), (end.y - start.y).toDouble()).toFloat()
    }

    /** Summed segment length along the path, in normalized units. */
    val arcLength: Float by lazy(LazyThreadSafetyMode.NONE) {
        var sum = 0f
        for (i in 1 until points.size) {
            sum += hypot((points[i].x - points[i - 1].x).toDouble(), (points[i].y - points[i - 1].y).toDouble()).toFloat()
        }
        sum
    }

    /**
     * displacement / arcLength in [0,1]. 1.0 is a perfectly straight path; a meandering
     * multi-key "type-by-swiping" trace scores low. This is the primary signal that
     * separates a deliberate flick/swipe from a flow-typing trace.
     */
    val straightness: Float by lazy(LazyThreadSafetyMode.NONE) {
        if (arcLength <= EPS) 1f else (displacement / arcLength).coerceIn(0f, 1f)
    }

    /** Dominant direction of travel, degrees in (-180,180]. 0 = right, 90 = down, -90 = up, ±180 = left. */
    val dominantAngleDeg: Float by lazy(LazyThreadSafetyMode.NONE) {
        if (points.size < 2) 0f
        else Math.toDegrees(atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())).toFloat()
    }

    /** Straight-line mean speed over the whole gesture, in normalized units per second. */
    val meanSpeed: Float by lazy(LazyThreadSafetyMode.NONE) {
        if (durationMs <= 0L) 0f else displacement / (durationMs / 1000f)
    }

    /**
     * Speed over the final [windowMs] before lift-off, in normalized units per second.
     *
     * This separates a flow-typed word from an action flick/swipe in the ambiguous
     * short-straight case: a swipe-typed word *arrives at and settles on* the target key
     * (low terminal speed), whereas an action flick is *ballistic* — the finger is still
     * moving fast at release (high terminal speed). Shape alone can't tell these apart
     * because two keys make a straight line; this captures intent instead.
     */
    fun terminalSpeed(windowMs: Long): Float {
        if (points.size < 2) return 0f
        val endP = points.last()
        var i = points.size - 1
        while (i > 0 && endP.t - points[i].t < windowMs) i--
        val startP = points[i]
        val dtMs = endP.t - startP.t
        if (dtMs <= 0L) return 0f
        return distanceBetween(startP, endP) / (dtMs / 1000f)
    }

    /** Largest extent of the trace along either axis (normalized) — used to detect "stayed put" holds/taps. */
    val maxExcursion: Float by lazy(LazyThreadSafetyMode.NONE) {
        var minX = start.x; var maxX = start.x; var minY = start.y; var maxY = start.y
        for (p in points) {
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        max(maxX - minX, maxY - minY)
    }

    companion object {
        const val EPS = 1e-5f

        /** Signed angular distance a→b in degrees, result in [-180,180]. */
        fun angleDelta(a: Float, b: Float): Float {
            var d = (b - a) % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }

        fun distanceBetween(a: TracePoint, b: TracePoint): Float =
            hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
    }
}

/** The four cardinal swipe directions. Classification snaps to the nearest sector (no dead zone). */
enum class GestureDirection { UP, DOWN, LEFT, RIGHT;
    companion object {
        /** Map a dominant angle (degrees, y-down) to the nearest cardinal direction; boundaries at ±45°. */
        fun fromAngle(angleDeg: Float): GestureDirection = when {
            angleDeg >= -45f && angleDeg < 45f -> RIGHT
            angleDeg >= 45f && angleDeg < 135f -> DOWN
            angleDeg >= -135f && angleDeg < -45f -> UP
            else -> LEFT
        }

        /** Angular distance (degrees) from [angleDeg] to the center of its chosen sector; 0 = dead-on. */
        fun marginFromSectorCenter(angleDeg: Float): Float {
            val center = when (fromAngle(angleDeg)) {
                RIGHT -> 0f; DOWN -> 90f; UP -> -90f; LEFT -> 180f
            }
            return abs(GestureTrace.angleDelta(center, angleDeg))
        }
    }
}
