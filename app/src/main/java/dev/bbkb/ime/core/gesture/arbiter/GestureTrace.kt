package dev.bbkb.ime.core.gesture.arbiter

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * One sampled point. Coordinates are normalized and isotropic (both axes divided by the same
 * reference dimension) so distances and angles compare across input sources; `y` grows downward.
 *
 * @param t event time in milliseconds
 */
data class TracePoint(val x: Float, val y: Float, val t: Long)

/**
 * One finger contact (DOWN .. UP/CANCEL) plus the whole-trace geometry [GestureClassifier] decides
 * from (instantaneous end velocity is too noisy on a small capacitive surface). Lazy values use
 * [LazyThreadSafetyMode.NONE]: traces are built and read on the main thread only.
 */
class GestureTrace(
    val points: List<TracePoint>,
    val pointerId: Int = 0,
) {
    val isDegenerate: Boolean get() = points.size < 2

    val start: TracePoint get() = points.first()
    val end: TracePoint get() = points.last()

    val durationMs: Long get() = if (points.size < 2) 0L else end.t - start.t

    /** Straight-line start to end distance. */
    val displacement: Float by lazy(LazyThreadSafetyMode.NONE) {
        if (points.size < 2) 0f else hypot((end.x - start.x).toDouble(), (end.y - start.y).toDouble()).toFloat()
    }

    val arcLength: Float by lazy(LazyThreadSafetyMode.NONE) {
        var sum = 0f
        for (i in 1 until points.size) {
            sum += hypot((points[i].x - points[i - 1].x).toDouble(), (points[i].y - points[i - 1].y).toDouble()).toFloat()
        }
        sum
    }

    /** displacement / arcLength in [0,1]; the primary swipe vs. flow-trace signal. */
    val straightness: Float by lazy(LazyThreadSafetyMode.NONE) {
        if (arcLength <= EPS) 1f else (displacement / arcLength).coerceIn(0f, 1f)
    }

    /** Degrees in (-180,180]: 0 right, 90 down, -90 up, ±180 left. */
    val dominantAngleDeg: Float by lazy(LazyThreadSafetyMode.NONE) {
        if (points.size < 2) 0f
        else Math.toDegrees(atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())).toFloat()
    }

    /** Normalized units per second, from displacement. */
    val meanSpeed: Float by lazy(LazyThreadSafetyMode.NONE) {
        if (durationMs <= 0L) 0f else displacement / (durationMs / 1000f)
    }

    /**
     * Speed over the final [windowMs]. A flow-typed word settles on its last key (low); an action
     * flick is still moving at release (high). Shape alone can't tell two-key flows from swipes.
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

    /** Largest extent along either axis, for "stayed put" taps and holds. */
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

/** Classification snaps to the nearest sector (no dead zone). */
enum class GestureDirection { UP, DOWN, LEFT, RIGHT;
    companion object {
        /** Degrees, y-down; boundaries at ±45°. */
        fun fromAngle(angleDeg: Float): GestureDirection = when {
            angleDeg >= -45f && angleDeg < 45f -> RIGHT
            angleDeg >= 45f && angleDeg < 135f -> DOWN
            angleDeg >= -135f && angleDeg < -45f -> UP
            else -> LEFT
        }

        /** Degrees from [angleDeg] to its sector's center; 0 is dead-on. */
        fun marginFromSectorCenter(angleDeg: Float): Float {
            val center = when (fromAngle(angleDeg)) {
                RIGHT -> 0f; DOWN -> 90f; UP -> -90f; LEFT -> 180f
            }
            return abs(GestureTrace.angleDelta(center, angleDeg))
        }
    }
}
