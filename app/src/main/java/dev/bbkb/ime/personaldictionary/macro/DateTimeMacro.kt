package dev.bbkb.ime.personaldictionary.macro

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Abstract base class for date/time dynamic macros.
 *
 * Subclasses define the format skeleton and whether to use ISO (US) locale.
 *
 * Two things this class is careful about:
 *  * A subclass whose [isSkeleton] is true supplies an ICU *skeleton* (a bag of
 *    field letters with no ordering or punctuation), which must be resolved to a
 *    real pattern for the target locale via
 *    [android.text.format.DateFormat.getBestDateTimePattern]. Handing a skeleton
 *    straight to [SimpleDateFormat] renders it literally
 *    ("Saturday06September2026" instead of "Saturday, September 6, 2026") and
 *    throws away locale day/month ordering entirely.
 *  * Macro expansion runs on the word-commit path, so the (expensive)
 *    [SimpleDateFormat] is cached per pattern+locale rather than reallocated per
 *    expansion. [SimpleDateFormat] is not thread-safe, hence the [ThreadLocal].
 */
internal abstract class DateTimeMacro : DynamicMacro {
    override fun getDynamicText(context: Context, locale: Locale): String {
        val effectiveLocale = if (isISO) Locale.US else locale
        val raw = getSkeleton(context)
        val pattern = if (isSkeleton) {
            android.text.format.DateFormat.getBestDateTimePattern(effectiveLocale, raw)
        } else {
            raw
        }
        return formatterFor(pattern, effectiveLocale).format(Date())
    }

    abstract fun getSkeleton(context: Context): String
    abstract val isISO: Boolean

    /**
     * True when [getSkeleton] returns an ICU skeleton rather than a literal
     * [SimpleDateFormat] pattern. Defaults to false — most macros already carry a
     * fully-formed pattern.
     */
    open val isSkeleton: Boolean get() = false

    companion object {
        private val CACHE = object : ThreadLocal<MutableMap<String, SimpleDateFormat>>() {
            override fun initialValue(): MutableMap<String, SimpleDateFormat> = HashMap(8)
        }

        private fun formatterFor(pattern: String, locale: Locale): SimpleDateFormat {
            val map = CACHE.get()!!
            val key = pattern + '|' + locale.toString()
            return map.getOrPut(key) { SimpleDateFormat(pattern, locale) }
        }
    }
}
