package dev.bbkb.ime.core.device.touch.shizuku

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Picks the input device by EVIOCGNAME, not node number (node numbers move between firmware
 * builds and boots). Exact compares the whole name; regex uses *search* semantics (`pad` matches
 * `mtk-pad` and `touch_keypad`), so anchor with `^...$` for a whole-name match. Travels to
 * [EvdevUserService] as (pattern, isRegex) and is rebuilt there so both sides agree.
 */
class TouchDeviceMatcher private constructor(
    val pattern: String,
    val isRegex: Boolean,
    private val regex: Regex?,
) {

    fun matches(name: String): Boolean = regex?.containsMatchIn(name) ?: (name == pattern)

    /** The first device in [devices] (callers pass them in node order) whose name matches. */
    fun firstMatch(devices: List<TouchDeviceInfo>): TouchDeviceInfo? = devices.firstOrNull { matches(it.name) }

    override fun equals(other: Any?): Boolean =
        other is TouchDeviceMatcher && other.pattern == pattern && other.isRegex == isRegex

    override fun hashCode(): Int = pattern.hashCode() * 31 + if (isRegex) 1 else 0

    override fun toString(): String = if (isRegex) "/$pattern/" else "'$pattern'"

    companion object {
        @JvmStatic
        fun exact(name: String): TouchDeviceMatcher {
            require(name.isNotEmpty()) { "device name is empty" }
            return TouchDeviceMatcher(name, false, null)
        }

        /** @throws IllegalArgumentException when java.util.regex rejects [pattern]. */
        @JvmStatic
        fun regex(pattern: String): TouchDeviceMatcher {
            require(pattern.isNotEmpty()) { "device pattern is empty" }
            val compiled = try {
                Regex(pattern)
            } catch (bad: PatternSyntaxException) {
                throw IllegalArgumentException("bad device pattern '$pattern': ${bad.description}", bad)
            }
            return TouchDeviceMatcher(pattern, true, compiled)
        }

        /** Only the case-insensitive option survives the trip to the reader (as inline `(?i)`). */
        @JvmStatic
        fun regex(regex: Regex): TouchDeviceMatcher {
            val caseless = regex.toPattern().flags() and Pattern.CASE_INSENSITIVE != 0
            return regex(if (caseless) "(?i)" + regex.pattern else regex.pattern)
        }

        /** The matcher for a (pattern, isRegex) pair off the wire, or null if it is unusable. */
        @JvmStatic
        fun parse(pattern: String?, isRegex: Boolean): TouchDeviceMatcher? = try {
            when {
                pattern.isNullOrEmpty() -> null
                isRegex -> regex(pattern)
                else -> exact(pattern)
            }
        } catch (bad: IllegalArgumentException) {
            null
        }
    }
}

/** The evdev node directory and its naming, kept pure for the tests. */
object EvdevNodes {
    const val INPUT_DIR = "/dev/input"
    private const val PREFIX = "event"

    /**
     * The `eventN` entries in numeric order (event2 before event10), so "first match" is the
     * lowest node, as `getevent` lists them. Other entries (mice, js0, links) are dropped.
     */
    @JvmStatic
    fun sortedEventNodes(names: Iterable<String>): List<String> = names
        .mapNotNull { name ->
            if (!name.startsWith(PREFIX)) return@mapNotNull null
            val n = name.substring(PREFIX.length)
            if (n.isEmpty() || !n.all { it in '0'..'9' }) return@mapNotNull null
            n.toIntOrNull()?.let { it to name }
        }
        .sortedBy { it.first }
        .map { it.second }
}
