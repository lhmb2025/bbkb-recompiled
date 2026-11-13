package dev.bbkb.ime.personaldictionary.macro

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Base for date/time macros. An ICU skeleton ([isSkeleton]) must be resolved through
 * `getBestDateTimePattern` for the locale; SimpleDateFormat would render it literally.
 * Formatters are cached per pattern and locale in a [ThreadLocal] (commit path, not thread-safe).
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

    /** True when [getSkeleton] returns an ICU skeleton rather than a SimpleDateFormat pattern. */
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
