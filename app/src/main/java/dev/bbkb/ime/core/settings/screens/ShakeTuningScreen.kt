package dev.bbkb.ime.core.settings.screens

import androidx.compose.runtime.Composable
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Slide
import dev.bbkb.ime.core.settings.ui.intPref

/**
 * Debug > Shake gesture tuning: the detector's thresholds (ShakeGestureHandler / ShakeDetector).
 * The actions a shake triggers are on the Shake gestures screen under Input.
 */
@Composable
fun ShakeTuningScreen(onNavigateBack: () -> Unit) {
    SettingsScreenHost(R.string.prefs_shake_tuning_title, onNavigateBack, listOf(
        Slide(intPref("shake_on_axis_trigger_count", 2),
            R.string.prefs_on_axis_trigger_count_title,
            R.string.prefs_on_axis_trigger_count_summary, 1..5),
        Slide(intPref("shake_fallback_trigger_count", 3),
            R.string.prefs_fallback_trigger_count_title,
            R.string.prefs_fallback_trigger_count_summary, 1..10),
        Slide(intPref("shake_acceleration_threshold", 15),
            R.string.prefs_acceleration_threshold_title,
            R.string.prefs_acceleration_threshold_summary, 5..30),
        Slide(intPref("shake_slop_time", 250),
            R.string.prefs_slop_time_title,
            R.string.prefs_slop_time_summary, 0..1000, step = 50, unit = "ms"),
        Slide(intPref("shake_reset_time", 500),
            R.string.prefs_reset_time_title,
            R.string.prefs_reset_time_summary, 100..2000, step = 100, unit = "ms"),
    ))
}
