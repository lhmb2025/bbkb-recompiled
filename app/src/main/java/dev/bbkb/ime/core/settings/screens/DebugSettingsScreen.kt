package dev.bbkb.ime.core.settings.screens

import android.content.Context
import androidx.compose.runtime.Composable
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.settings.ui.Category
import dev.bbkb.ime.core.settings.ui.Choice
import dev.bbkb.ime.core.settings.ui.ChoiceOption
import dev.bbkb.ime.core.settings.ui.Custom
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.PreferenceScreen
import dev.bbkb.ime.core.settings.ui.RowSummary
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Slide
import dev.bbkb.ime.core.settings.ui.Toggle
import dev.bbkb.ime.core.settings.ui.boolPref
import dev.bbkb.ime.core.settings.ui.intPref
import dev.bbkb.ime.core.settings.ui.intPrefRes
import dev.bbkb.ime.core.settings.ui.stringPref
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.R

private const val CURSOR_BEYOND_FIELD = "pref_key_allow_horizontal_cursor_beyond_field"

/**
 * Debug Settings Screen - Power user configuration options
 * Organized into logical categories for keyboard behavior customization
 */
@Composable
fun DebugSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAdvancedGestureParameters: () -> Unit = {},
    onNavigateToAnimationParameters: () -> Unit = {},
    onNavigateToGestureLab: () -> Unit = {}
) {
    SettingsScreenHost(R.string.prefs_advanced_settings_title, onNavigateBack, listOf(
        Category(R.string.prefs_category_timing_responsiveness),
        Slide(intPref("pref_key_longpress_timeout", 400),
            R.string.prefs_key_longpress_delay_title,
            R.string.prefs_key_longpress_delay_summary, 100..1000, step = 50, unit = "ms"),
        Slide(intPref("pref_slideboard_key_longpress_timeout", 400),
            R.string.prefs_slideboard_longpress_delay_title,
            R.string.prefs_slideboard_longpress_delay_summary, 100..1000, step = 50, unit = "ms"),

        Category(R.string.prefs_category_swipe_flow_typing),
        Slide(intPref("pref_key_flow_mode_vertical_swipe_min_y", 40),
            R.string.prefs_flow_mode_min_y_title,
            R.string.prefs_flow_mode_min_y_summary, 10..100, step = 5, unit = "px"),
        Slide(intPref("pref_key_flow_mode_vertical_swipe_min_velocity", 600),
            R.string.prefs_flow_mode_min_velocity_title,
            R.string.prefs_flow_mode_min_velocity_summary, 100..1200, step = 50, unit = "px/s"),

        Category(R.string.prefs_category_gesture_sensitivity),
        // Same resource default SettingsValues and AdvancedGestureParametersScreen read (the
        // original APK's debug screen did too). The CKB twin of this row lives on
        // CkbGesturesScreen now — the screen a user actually looks at for CKB gestures.
        Slide(intPrefRes("pref_VKB_gesture_suppression_timeout", R.integer.config_default_VKB_swipe_gesture_suppression_timeout),
            R.string.prefs_vkb_gesture_activation_delay_title,
            R.string.prefs_vkb_gesture_activation_delay_summary, 0..200, step = 10, unit = "ms"),
        Nav(
            title = R.string.prefs_advanced_gesture_thresholds_title,
            summary = R.string.prefs_advanced_gesture_thresholds_summary,
            onClick = { onNavigateToAdvancedGestureParameters() },
        ),
        // Hardcoded copy, so it rides the escape hatch rather than inventing a literal-title row.
        Custom {
            PreferenceScreen(
                title = "Gesture Lab (prototype)",
                summary = "Visualize swipes and see the new gesture classifier's verdict; tune thresholds live",
                onClick = { onNavigateToGestureLab() }
            )
        },

        Category(R.string.prefs_category_visual_feedback),
        Toggle(boolPref("pref_show_ui_to_accept_typed_word", true),
            R.string.prefs_typed_word_accept_button_title,
            RowSummary.Res(R.string.prefs_typed_word_accept_button_summary)),
        Toggle(boolPref("pref_sliding_key_input_preview", true),
            R.string.prefs_slide_to_type_preview_title,
            RowSummary.Res(R.string.prefs_slide_to_type_preview_summary)),
        Toggle(boolPref("pref_custom_symbol_page_overlay_show_always", false),
            R.string.prefs_persistent_symbol_page_hint_title,
            RowSummary.Res(R.string.prefs_persistent_symbol_page_hint_summary)),
        Nav(
            title = R.string.prefs_key_preview_animations_title,
            summary = R.string.prefs_key_preview_animations_summary,
            onClick = { onNavigateToAnimationParameters() },
        ),

        Category(R.string.prefs_category_physical_keyboard),
        Choice(
            store = stringPref("pref_show_on_keypress_mode", "2"),
            title = R.string.prefs_virtual_keyboard_visibility_title,
            options = ::vkbVisibilities,
            // The summary is the shared blurb plus a per-value status clause, not the option label.
            summary = ::vkbVisibilitySummary,
        ),
        Toggle(boolPref("pref_mic_voice_assistant_enabled", false),
            R.string.prefs_mic_key_triggers_voice_assistant_title,
            RowSummary.Res(R.string.prefs_mic_key_triggers_voice_assistant_summary)),

        Category(R.string.prefs_category_cursor_control),
        Toggle(boolPref(CURSOR_BEYOND_FIELD, false),
            R.string.prefs_cross_field_boundaries_title,
            RowSummary.Res(R.string.prefs_cross_field_boundaries_summary)),
        Toggle(boolPref("pref_key_allow_batched_cursor_move", true),
            R.string.prefs_batch_cursor_movements_title,
            RowSummary.Res(R.string.prefs_batch_cursor_movements_summary),
            enabled = { !it.bool(CURSOR_BEYOND_FIELD) }),

        Category(R.string.prefs_category_slideboard),
        Toggle(boolPref("pref_slideboard_still_boards", true),
            R.string.prefs_disable_auto_scroll_title,
            RowSummary.Res(R.string.prefs_disable_auto_scroll_summary)),

        Category(R.string.prefs_category_korean_input),
        Toggle(boolPref("pref_korean_double_consonant_resolution", false),
            R.string.prefs_double_consonant_timing_title,
            RowSummary.Res(R.string.prefs_double_consonant_timing_summary)),
        Slide(intPref("pref_korean_double_consonant_resolution_delay",
                SettingsManager.DEFAULT_KOREAN_DOUBLE_CONSONANT_RESOLUTION_DELAY_MS),
            R.string.prefs_double_consonant_delay_title,
            R.string.prefs_double_consonant_delay_summary, 100..1000, step = 50, unit = "ms"),

        Category(R.string.prefs_category_shake_gestures),
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

        Category(R.string.prefs_category_testing_diagnostics),
        Toggle(boolPref("debug_force_vkb_mode", false),
            R.string.prefs_force_vkb_mode_title,
            RowSummary.OnOff(
                R.string.prefs_force_vkb_mode_summary_on,
                R.string.prefs_force_vkb_mode_summary_off,
            ),
            // Apply live: the pref alone is only read at DeviceProfile.initialize(), and the
            // transient force flag is cleared on every hideWindow().
            onWrite = { _, on -> DeviceProfile.setDebugForceVkbMode(on) }),
        Toggle(boolPref("force_physical_keyboard_special_key", false),
            R.string.prefs_pkb_special_key_handling_title,
            RowSummary.Res(R.string.prefs_pkb_special_key_handling_summary)),
        Toggle(boolPref("pref_physical_keyboard_debug_mode", false),
            R.string.prefs_pkb_debug_logging_title,
            RowSummary.Res(R.string.prefs_pkb_debug_logging_summary)),
        Toggle(boolPref("pref_debug_autofill_logging", false),
            R.string.prefs_autofill_debug_logging_title,
            RowSummary.Res(R.string.prefs_autofill_debug_logging_summary)),
        Toggle(boolPref("pref_debug_force_autofill_bar", false),
            R.string.prefs_autofill_event_toasts_title,
            RowSummary.Res(R.string.prefs_autofill_event_toasts_summary)),
        // The profile builder is half-finished, so DeviceConfigurationScreen hides its whole
        // "THIS DEVICE" section unless this is on. The route stays live either way — the builder
        // is still reachable by deep link and by its own tests.
        Toggle(boolPref(PREF_SHOW_DEVICE_PROFILE_BUILDER, false),
            R.string.prefs_show_device_profile_builder_title,
            RowSummary.Res(R.string.prefs_show_device_profile_builder_summary)),
        // The CKB gesture screen's DEVELOPER category — sensor-map capture and export — is
        // instrumentation for the decoder, not settings, so it is hidden unless this is on.
        // Nothing there is deleted; the rest of that screen renders either way.
        Toggle(boolPref(PREF_SHOW_CKB_DEVELOPER_SETTINGS, false),
            R.string.prefs_show_ckb_developer_settings_title,
            RowSummary.Res(R.string.prefs_show_ckb_developer_settings_summary)),
    ))
}

private fun vkbVisibilities(context: Context) = listOf(
    ChoiceOption("0", context.getString(R.string.prefs_vkb_visibility_never)),
    ChoiceOption("1", context.getString(R.string.prefs_vkb_visibility_on_keypress)),
    ChoiceOption("2", context.getString(R.string.prefs_vkb_visibility_always)),
)

private fun vkbVisibilitySummary(context: Context, value: String): String {
    val status = context.getString(
        when (value) {
            "0" -> R.string.prefs_vkb_visibility_status_never
            "1" -> R.string.prefs_vkb_visibility_status_on_keypress
            "2" -> R.string.prefs_vkb_visibility_status_always
            else -> R.string.prefs_vkb_visibility_status_unknown
        }
    )
    return "${context.getString(R.string.prefs_virtual_keyboard_visibility_summary)} $status"
}
