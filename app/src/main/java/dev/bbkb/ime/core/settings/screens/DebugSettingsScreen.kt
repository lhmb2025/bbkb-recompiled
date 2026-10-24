package dev.bbkb.ime.core.settings.screens

import androidx.compose.runtime.Composable
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.settings.ui.Category
import dev.bbkb.ime.core.settings.ui.Custom
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.PreferenceScreen
import dev.bbkb.ime.core.settings.ui.RowSummary
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Toggle
import dev.bbkb.ime.core.settings.ui.boolPref

/**
 * Debug settings (debug builds only; the Advanced hub hides the entry in release builds):
 * advanced tuning that a user has no everyday reason to touch, and developer switches.
 *
 * 2026-10-20: the rows nothing read are gone (flow-mode swipe thresholds, slide-to-type preview,
 * persistent symbol page hint, virtual keyboard visibility, mic key voice assistant, cursor
 * control, Korean double consonants, physical special-key and debug-logging toggles). The key
 * long-press delay moved to On-screen keyboard > Behavior, the typed-word accept button to
 * Learning, and the VKB gesture activation delay slider was a duplicate of the one on Advanced
 * gesture thresholds.
 */
@Composable
fun DebugSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAdvancedGestureParameters: () -> Unit = {},
    onNavigateToAnimationParameters: () -> Unit = {},
    onNavigateToGestureLab: () -> Unit = {},
    onNavigateToShakeTuning: () -> Unit = {},
) {
    SettingsScreenHost(R.string.prefs_advanced_settings_title, onNavigateBack, listOf(
        Category(R.string.prefs_category_tuning),
        Nav(
            title = R.string.prefs_advanced_gesture_thresholds_title,
            summary = R.string.prefs_advanced_gesture_thresholds_summary,
            onClick = { onNavigateToAdvancedGestureParameters() },
        ),
        Nav(
            title = R.string.prefs_shake_tuning_title,
            summary = R.string.prefs_shake_tuning_summary,
            onClick = { onNavigateToShakeTuning() },
        ),
        Nav(
            title = R.string.prefs_key_preview_animations_title,
            summary = R.string.prefs_key_preview_animations_summary,
            onClick = { onNavigateToAnimationParameters() },
        ),
        Toggle(boolPref("pref_slideboard_still_boards", true),
            R.string.prefs_disable_auto_scroll_title,
            RowSummary.Res(R.string.prefs_disable_auto_scroll_summary)),

        Category(R.string.prefs_category_diagnostics),
        Toggle(boolPref("debug_force_vkb_mode", false),
            R.string.prefs_force_vkb_mode_title,
            RowSummary.OnOff(
                R.string.prefs_force_vkb_mode_summary_on,
                R.string.prefs_force_vkb_mode_summary_off,
            ),
            // Apply live: the pref alone is only read at DeviceProfile.initialize(), and the
            // transient force flag is cleared on every hideWindow().
            onWrite = { _, on -> DeviceProfile.setDebugForceVkbMode(on) }),
        Toggle(boolPref("pref_debug_autofill_logging", false),
            R.string.prefs_autofill_debug_logging_title,
            RowSummary.Res(R.string.prefs_autofill_debug_logging_summary)),
        Toggle(boolPref("pref_debug_force_autofill_bar", false),
            R.string.prefs_autofill_event_toasts_title,
            RowSummary.Res(R.string.prefs_autofill_event_toasts_summary)),
        // Hardcoded copy, so it rides the escape hatch rather than inventing a literal-title row.
        Custom(inGroup = true) {
            PreferenceScreen(
                title = "Gesture Lab (prototype)",
                summary = "Visualize swipes and see the new gesture classifier's verdict; tune thresholds live",
                onClick = { onNavigateToGestureLab() }
            )
        },

        Category(R.string.prefs_category_developer),
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
