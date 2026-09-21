package dev.bbkb.ime.core.settings.screens

import androidx.compose.runtime.Composable
import dev.bbkb.ime.core.settings.ui.Category
import dev.bbkb.ime.core.settings.ui.RowSummary
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Slide
import dev.bbkb.ime.core.settings.ui.Toggle
import dev.bbkb.ime.core.settings.ui.boolPref
import dev.bbkb.ime.core.settings.ui.intPrefRes
import dev.bbkb.ime.core.settings.ui.percentPref
import dev.bbkb.ime.R

/**
 * Advanced Gesture Parameters Screen
 * Detailed configuration for gesture timing, thresholds, and cursor control
 *
 * The four cursor tap-region scales are stored as FRACTIONS, because SettingsValues reads them
 * with getFloat while these sliders work in whole percent; [percentPref] carries that conversion
 * and the tolerance for a legacy int-typed entry that an older version of this screen wrote.
 */
@Composable
fun AdvancedGestureParametersScreen(
    onNavigateBack: () -> Unit
) {
    SettingsScreenHost(R.string.settings_advanced_gesture_params_title, onNavigateBack, listOf(
        // The CKB suppression timeout used to head this category. It is the one timing value a
        // user reaches for, so it moved to CkbGesturesScreen; this screen keeps the rest.
        Category(R.string.settings_gesture_category_timing),
        Slide(intPrefRes("pref_VKB_gesture_suppression_timeout", R.integer.config_default_VKB_swipe_gesture_suppression_timeout), R.string.settings_gesture_vkb_suppression_title,
            R.string.settings_gesture_vkb_suppression_summary, 50..500, step = 10, unit = "ms"),
        Slide(intPrefRes("pref_accent_selection_suppression_timeout", R.integer.config_default_accent_selection_suppression_timeout), R.string.settings_gesture_accent_suppression_title,
            R.string.settings_gesture_accent_suppression_summary, 50..500, step = 10, unit = "ms"),
        Slide(intPrefRes("pref_doubletap_suppression_timeout", R.integer.config_default_doubletap_suppression_timeout), R.string.settings_gesture_doubletap_suppression_title,
            R.string.settings_gesture_doubletap_suppression_summary, 50..500, step = 10, unit = "ms"),
        Toggle(boolPref("pref_key_swipe_suppression_timeout_applies_to_end", R.bool.config_default_apply_swipe_suppression_timeout_to_swipe_end), R.string.settings_gesture_swipe_suppression_end_title,
            RowSummary.Res(R.string.settings_gesture_swipe_suppression_end_summary)),
        Slide(intPrefRes("pref_key_swipe_gesture_timeout", R.integer.config_default_swipe_gesture_timeout), R.string.settings_gesture_swipe_timeout_title,
            R.string.settings_gesture_swipe_timeout_summary, 100..1000, step = 50, unit = "ms"),
        Category(R.string.settings_gesture_category_angles),
        Slide(intPrefRes("pref_key_horizontal_swipe_theta", R.integer.config_default_horizontal_swipe_theta), R.string.settings_gesture_horiz_theta_title,
            R.string.settings_gesture_horiz_theta_summary, 0..90, unit = "°"),
        Slide(intPrefRes("pref_key_vertical_swipe_theta", R.integer.config_default_vertical_swipe_theta), R.string.settings_gesture_vert_theta_title,
            R.string.settings_gesture_vert_theta_summary, 0..90, unit = "°"),
        Category(R.string.settings_gesture_category_distance),
        Slide(intPrefRes("pref_key_fast_horizontal_swipe_min_x", R.integer.config_default_fast_horizontal_swipe_min_x), R.string.settings_gesture_fast_horiz_min_x_title,
            R.string.settings_gesture_fast_horiz_min_x_summary, 10..300, step = 10, unit = "px"),
        Slide(intPrefRes("pref_key_slow_horizontal_swipe_min_x", R.integer.config_default_slow_horizontal_swipe_min_x), R.string.settings_gesture_slow_horiz_min_x_title,
            R.string.settings_gesture_slow_horiz_min_x_summary, 10..200, step = 10, unit = "px"),
        Slide(intPrefRes("pref_key_fast_vertical_swipe_min_y", R.integer.config_default_fast_vertical_swipe_min_y), R.string.settings_gesture_fast_vert_min_y_title,
            R.string.settings_gesture_fast_vert_min_y_summary, 10..300, step = 10, unit = "px"),
        Slide(intPrefRes("pref_key_slow_vertical_swipe_min_y", R.integer.config_default_slow_vertical_swipe_min_y), R.string.settings_gesture_slow_vert_min_y_title,
            R.string.settings_gesture_slow_vert_min_y_summary, 10..200, step = 10, unit = "px"),
        Category(R.string.settings_gesture_category_velocity),
        Slide(intPrefRes("pref_key_fast_horizontal_swipe_min_velocity", R.integer.config_default_fast_horizontal_swipe_min_velocity), R.string.settings_gesture_fast_horiz_vel_title,
            R.string.settings_gesture_fast_horiz_vel_summary, 100..3000, step = 100, unit = "px/s"),
        Slide(intPrefRes("pref_key_slow_horizontal_swipe_min_velocity", R.integer.config_default_slow_horizontal_swipe_min_velocity), R.string.settings_gesture_slow_horiz_vel_title,
            R.string.settings_gesture_slow_horiz_vel_summary, 100..2000, step = 100, unit = "px/s"),
        Slide(intPrefRes("pref_key_fast_vertical_swipe_min_velocity", R.integer.config_default_fast_vertical_swipe_min_velocity), R.string.settings_gesture_fast_vert_vel_title,
            R.string.settings_gesture_fast_vert_vel_summary, 100..3000, step = 100, unit = "px/s"),
        Slide(intPrefRes("pref_key_slow_vertical_swipe_min_velocity", R.integer.config_default_slow_vertical_swipe_min_velocity), R.string.settings_gesture_slow_vert_vel_title,
            R.string.settings_gesture_slow_vert_vel_summary, 100..2000, step = 100, unit = "px/s"),
        Category(R.string.settings_gesture_category_tap_regions),
        Slide(percentPref("pref_key_horizontal_cursor_tap_region_height_scale", R.fraction.config_default_horizontal_cursor_tap_region_height_scale), R.string.settings_gesture_horiz_tap_height_title,
            R.string.settings_gesture_horiz_tap_height_summary, 10..200, step = 10, unit = "%"),
        Slide(percentPref("pref_key_horizontal_cursor_tap_region_width_scale", R.fraction.config_default_horizontal_cursor_tap_region_width_scale), R.string.settings_gesture_horiz_tap_width_title,
            R.string.settings_gesture_horiz_tap_width_summary, 10..200, step = 10, unit = "%"),
        Slide(percentPref("pref_key_vertical_cursor_tap_region_height_scale", R.fraction.config_default_vertical_cursor_tap_region_height_scale), R.string.settings_gesture_vert_tap_height_title,
            R.string.settings_gesture_vert_tap_height_summary, 10..200, step = 10, unit = "%"),
        Slide(percentPref("pref_key_vertical_cursor_tap_region_width_scale", R.fraction.config_default_vertical_cursor_tap_region_width_scale), R.string.settings_gesture_vert_tap_width_title,
            R.string.settings_gesture_vert_tap_width_summary, 10..200, step = 10, unit = "%"),
        Category(R.string.settings_gesture_category_scroll),
        Slide(intPrefRes("pref_key_scroll_horizontal_distance_for_cursor_move", R.integer.config_default_horizontal_scroll_distance_for_cursor_move), R.string.settings_gesture_scroll_horiz_cursor_title,
            R.string.settings_gesture_scroll_horiz_cursor_summary, 5..100, step = 5, unit = "px"),
        Slide(intPrefRes("pref_key_scroll_vertical_distance_for_cursor_move", R.integer.config_default_vertical_scroll_distance_for_cursor_move), R.string.settings_gesture_scroll_vert_cursor_title,
            R.string.settings_gesture_scroll_vert_cursor_summary, 5..100, step = 5, unit = "px"),
        Slide(intPrefRes("pref_key_scroll_single_line_vertical_distance_for_cursor_move", R.integer.config_default_single_line_vertical_scroll_distance_for_cursor_move), R.string.settings_gesture_scroll_single_line_title,
            R.string.settings_gesture_scroll_single_line_summary, 5..100, step = 5, unit = "px"),
        Slide(intPrefRes("pref_key_scroll_horizontal_distance_for_accents_change", R.integer.config_default_horizontal_scroll_distance_for_accents_change), R.string.settings_gesture_scroll_horiz_accent_title,
            R.string.settings_gesture_scroll_horiz_accent_summary, 5..100, step = 5, unit = "px"),
        Category(R.string.settings_gesture_category_speed),
        Slide(intPrefRes("pref_key_scroll_cursor_move_max_speed_multiplier", R.integer.config_default_max_cursor_move_speed_multiplier), R.string.settings_gesture_cursor_max_speed_title,
            R.string.settings_gesture_cursor_max_speed_summary, 1..10, unit = "x"),
        Slide(intPrefRes("pref_key_cursor_move_velocity_for_max_speed_multiplier", R.integer.config_default_velocity_for_max_cursor_move_speed), R.string.settings_gesture_cursor_vel_max_speed_title,
            R.string.settings_gesture_cursor_vel_max_speed_summary, 500..5000, step = 100, unit = "px/s"),
        Slide(intPrefRes("pref_key_scroll_accents_change_max_speed_multiplier", R.integer.config_default_max_accents_move_speed_multiplier), R.string.settings_gesture_accent_max_speed_title,
            R.string.settings_gesture_accent_max_speed_summary, 1..10, unit = "x"),
        Category(R.string.settings_gesture_category_word_detection),
        Slide(intPrefRes("pref_in_letter_max_swipe_to_word_distance", R.integer.config_default_in_letter_max_swipe_to_word_distance), R.string.settings_gesture_swipe_word_dist_title,
            R.string.settings_gesture_swipe_word_dist_summary, 10..300, step = 10, unit = "px"),
    ))
}
