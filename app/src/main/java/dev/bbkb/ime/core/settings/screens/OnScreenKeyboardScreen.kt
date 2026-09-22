package dev.bbkb.ime.core.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.bbkb.ime.core.locale.LocaleUtils
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.Category
import dev.bbkb.ime.core.settings.ui.Custom
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.RowSummary
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Toggle
import dev.bbkb.ime.core.settings.ui.boolPref
import dev.bbkb.ime.R

/**
 * On-Screen Keyboard Settings Screen
 * Categories: Gestures, Behavior (Ctrl key, unified input menu, slideboard, key-press feedback).
 *
 * The custom symbol page still lives under Appearance & layout; Slideboard settings moved here,
 * because everything it configures is on-screen-keyboard behaviour.
 */
@Composable
fun TouchScreenKeyboardScreen(
    onNavigateBack: () -> Unit,
    onNavigateToKeyPressFeedback: () -> Unit = {},
    onNavigateToCustomizeMenu: () -> Unit = {},
    onNavigateToSlideboard: () -> Unit = {}
) {
    // Chinese locale disables swipe typing (NuanceSDK limitation)
    val isChineseLocale = remember { LocaleUtils.isCurrentSubtypeChinese() }

    SettingsScreenHost(R.string.settings_keyboard_title, onNavigateBack, listOf(
        Category(R.string.settings_category_gestures),
        Toggle(
            store = boolPref("type_by_swiping_vkb", R.bool.config_vkb_type_by_swiping_enabled_by_build_config),
            title = R.string.settings_vkb_type_by_swiping_title,
            summary = RowSummary.Res(R.string.settings_vkb_type_by_swiping_summary),
            enabled = { !isChineseLocale },
            modifier = Modifier.settingsSearchAnchor("type_by_swiping_vkb"),
        ),
        Custom(visible = { isChineseLocale }) { ChineseLocaleSwipeWarning() },
        Toggle(
            store = boolPref("swipe_gesture_vkb", R.bool.config_vkb_swipe_gestures_enabled_by_build_config),
            title = R.string.settings_vkb_swipe_gestures_title,
            summary = RowSummary.Res(R.string.settings_vkb_swipe_gestures_summary),
            modifier = Modifier.settingsSearchAnchor("swipe_gesture_vkb"),
        ),
        Toggle(
            store = boolPref("swipe_down_vkb", true),
            title = R.string.settings_vkb_swipe_down_dismiss_title,
            summary = RowSummary.Res(R.string.settings_vkb_swipe_down_dismiss_summary),
            modifier = Modifier.settingsSearchAnchor("swipe_down_vkb"),
        ),

        Category(R.string.settings_category_behavior_vkb),
        Toggle(
            store = boolPref("vkb_control_mode_enabled", false),
            title = R.string.vkb_control_key_title,
            summary = RowSummary.Res(R.string.vkb_control_key_summary),
            modifier = Modifier.settingsSearchAnchor("vkb_control_mode_enabled"),
        ),
        Toggle(
            store = boolPref("pref_uim_enabled", true),
            title = R.string.settings_uim_enable_title,
            summary = RowSummary.OnOff(
                R.string.settings_uim_enable_summary_on,
                R.string.settings_uim_enable_summary_off,
            ),
            modifier = Modifier.settingsSearchAnchor("pref_uim_enabled"),
        ),
        // Reorder the unified input menu shortcuts (voice, emoji, cursor, clipboard, number pad).
        // There is nothing to customize while the menu itself is off, so the row greys out with
        // the toggle above it rather than opening a screen that configures a hidden menu.
        Nav(
            title = R.string.settings_customize_menu_title,
            summary = R.string.settings_customize_menu_summary,
            enabled = { it.bool("pref_uim_enabled") },
            onClick = { onNavigateToCustomizeMenu() },
        ),
        // Slideboard (enable, swap sides, number pad, quick phrases). No icon: the rows around it
        // carry none.
        Nav(
            title = R.string.settings_slideboard_settings_title,
            summary = R.string.settings_slideboard_hub_summary,
            onClick = { onNavigateToSlideboard() },
        ),
        // Touch feedback (links to KeyPressFeedbackScreen) — VKB only
        Nav(
            title = R.string.settings_touch_feedback_title,
            summary = R.string.settings_touch_feedback_summary,
            onClick = { onNavigateToKeyPressFeedback() },
        ),
    ))
}
