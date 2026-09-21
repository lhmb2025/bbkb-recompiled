package dev.bbkb.ime.personaldictionary.macro

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.annotation.VisibleForTesting
import java.util.Locale

/**
 * Defines available dynamic macro types and their handlers.
 *
 * | Tag | Description |
 * |-----|-------------|
 * | `%D` | Full date (e.g., "Saturday, January 25, 2026") |
 * | `%T` | Full time (e.g., "13:21:00" or "1:21:00 PM") |
 * | `%d` | ISO date (e.g., "2026-01-25") |
 * | `%t` | Short time (e.g., "13:21") |
 * | `%n` | Newline character |
 * | `%w` | Weekday name (e.g., "Monday") |
 * | `%y` | Year (e.g., "2026") |
 * | `%b` | Battery level (e.g., "85%") |
 */
enum class DynamicMacroType(
    val tag: String,
    val handler: DynamicMacro,
    val canExpectNullOrEmpty: Boolean
) {
    DATE("%D", object : DateTimeMacro() {
        // A genuine ICU *skeleton*, not a pattern: it must go through
        // DateFormat.getBestDateTimePattern() or SimpleDateFormat renders it
        // literally as "Saturday06September2026".
        override fun getSkeleton(context: Context): String = "EEEEddMMMMyyyy"
        override val isSkeleton: Boolean = true
        override val isISO: Boolean = false
    }, false),

    TIME("%T", object : DateTimeMacro() {
        override val isISO: Boolean = false
        override fun getSkeleton(context: Context): String {
            return if (DateFormat.is24HourFormat(context)) TIME_FORMAT_24HR else TIME_FORMAT_12HR
        }
    }, false),

    SHORTDATE("%d", object : DateTimeMacro() {
        override fun getSkeleton(context: Context): String = "yyyy-MM-dd"
        override val isISO: Boolean = true
    }, false),

    SHORTTIME("%t", object : DateTimeMacro() {
        override fun getSkeleton(context: Context): String = "HH:mm"
        override val isISO: Boolean = true
    }, false),

    NEWLINE("%n", object : DynamicMacro {
        override fun getDynamicText(context: Context, locale: Locale): String = "\n"
    }, false),

    WEEKDAY("%w", object : DateTimeMacro() {
        override fun getSkeleton(context: Context): String = "EEEE"
        override val isISO: Boolean = false
    }, false),

    YEAR("%y", object : DateTimeMacro() {
        override fun getSkeleton(context: Context): String = "yyyy"
        override val isISO: Boolean = true
    }, false),

    BATTERY("%b", object : DynamicMacro {
        override fun getDynamicText(context: Context, locale: Locale): String {
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                context.registerReceiver(null, filter)
            }
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            return if (percent >= 0) "$percent%" else "?%"
        }
    }, false)

    ;

    companion object {
        /**
         * Restored from the original APK decompile
         * (`com.blackberry.basl.DynamicMacroType.TimeMacro`): a global identifier
         * rename had replaced the trailing AM/PM pattern letter with the class name
         * `SuggestedWordInfo`, which `SimpleDateFormat` rejects with
         * `IllegalArgumentException` in every 12-hour locale.
         */
        @VisibleForTesting
        const val TIME_FORMAT_12HR = "hh:mm:ss a"

        @VisibleForTesting
        const val TIME_FORMAT_24HR = "HH:mm:ss"
    }
}
