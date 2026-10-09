package dev.bbkb.ime.core.device.touch.shizuku

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Which input device to read, chosen by its EVIOCGNAME rather than by node number: node numbers
 * move between firmware builds and even between boots (the Titan 2 pad went event7 -> event6 in
 * FW 20260422), names do not.
 *
 * An exact matcher compares the whole name, case and all. A regex matcher uses java.util.regex
 * and *search* semantics (like grep or POSIX regexec): `pad` matches both `mtk-pad` and
 * `touch_keypad`, so anchor with `^...$` when the whole name must match.
 *
 * The matcher travels to [EvdevUserService] as (pattern, isRegex) and is rebuilt there, so the
 * IME and the reader always agree on what matches.
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
        /** Matches a device named exactly [name]. */
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

        /**
         * A matcher from a Kotlin [Regex]. Only the case-insensitive option survives the trip to
         * the reader process (as an inline `(?i)`); the others mean nothing for a device name.
         */
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
     * The `eventN` entries of a /dev/input listing in numeric order (event2 before event10), so
     * "the first match" means the lowest node, the way `getevent` lists them. Anything else in
     * the directory (mice, js0, by-path links) is dropped.
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
