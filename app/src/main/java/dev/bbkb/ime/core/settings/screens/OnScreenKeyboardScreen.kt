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
import dev.bbkb.ime.core.settings.ui.intPref
import dev.bbkb.ime.core.settings.ui.Slide
import dev.bbkb.ime.core.settings.ui.boolPref
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.ui.drawableIcon

/**
 * On-Screen Keyboard Settings Screen
 * Categories: Behavior (Ctrl key, long-press delay, flick animation), Gestures, Emoji, then one
 * link each under Slideboard and Feedback.
 *
 * The custom symbol page still lives under Appearance & layout; Slideboard settings moved here,
 * because everything it configures is on-screen-keyboard behaviour.
 */
@Composable
fun TouchScreenKeyboardScreen(
    onNavigateBack: () -> Unit,
    onNavigateToKeyPressFeedback: () -> Unit = {},
    onNavigateToSlideboard: () -> Unit = {},
) {
    // Chinese locale disables swipe typing (NuanceSDK limitation)
    val isChineseLocale = remember { LocaleUtils.isCurrentSubtypeChinese() }

    SettingsScreenHost(R.string.settings_keyboard_title, onNavigateBack, listOf(
        Category(R.string.settings_category_behavior_vkb),
        Toggle(
            store = boolPref("vkb_control_mode_enabled", false),
            title = R.string.vkb_control_key_title,
            icon = drawableIcon(R.drawable.ic_settings_ctrl_key),
            summary = RowSummary.Res(R.string.vkb_control_key_summary),
            modifier = Modifier.settingsSearchAnchor("vkb_control_mode_enabled"),
        ),
        // How long a key is held before its alternates pop up (the physical Alt key's accent
        // popup uses twice this). Was two Debug sliders, one swapped in while the slideboard was
        // on (2026-10-20).
        Slide(intPref("pref_key_longpress_timeout", 400),
            R.string.prefs_key_longpress_delay_title,
            R.string.prefs_key_longpress_delay_summary, 100..1000, step = 50, unit = "ms",
            valueOnRow = true,
            icon = drawableIcon(R.drawable.ic_settings_gesture_delay),
            modifier = Modifier.settingsSearchAnchor("pref_key_longpress_timeout")),
        // The committed word floating up on a flick: an on-screen animation, so it lives here
        // rather than under Suggestions (2026-10-20).
        Toggle(
            store = boolPref("pref_flick_commit_animation", true),
            title = R.string.settings_pred_flick_animation_title,
            icon = drawableIcon(R.drawable.ic_settings_flick_animation),
            summary = RowSummary.Res(R.string.settings_pred_flick_animation_summary),
            modifier = Modifier.settingsSearchAnchor("pref_flick_commit_animation"),
        ),
        Category(R.string.settings_category_gestures),
        Toggle(
            store = boolPref("type_by_swiping_vkb", R.bool.config_vkb_type_by_swiping_enabled_by_build_config),
            title = R.string.settings_vkb_type_by_swiping_title,
            icon = drawableIcon(R.drawable.ic_settings_swipe_typing),
            summary = RowSummary.Res(R.string.settings_vkb_type_by_swiping_summary),
            enabled = { !isChineseLocale },
            modifier = Modifier.settingsSearchAnchor("type_by_swiping_vkb"),
        ),
        Custom(visible = { isChineseLocale }) { ChineseLocaleSwipeWarning() },
        Toggle(
            store = boolPref("swipe_gesture_vkb", R.bool.config_vkb_swipe_gestures_enabled_by_build_config),
            title = R.string.settings_vkb_swipe_gestures_title,
            icon = drawableIcon(R.drawable.ic_settings_gesture_ckb),
            summary = RowSummary.Res(R.string.settings_vkb_swipe_gestures_summary),
            modifier = Modifier.settingsSearchAnchor("swipe_gesture_vkb"),
        ),
        Toggle(
            store = boolPref("swipe_down_vkb", true),
            title = R.string.settings_vkb_swipe_down_dismiss_title,
            icon = drawableIcon(R.drawable.ic_settings_swipe_down_dismiss),
            summary = RowSummary.Res(R.string.settings_vkb_swipe_down_dismiss_summary),
            modifier = Modifier.settingsSearchAnchor("swipe_down_vkb"),
        ),

        // On-screen only (2026-11-03): the emoji board opens filtered by the word being composed,
        // and a pick can replace that word. A physical keyboard has its own "Enable emoji search"
        // on the Physical keyboard screen, which types into the open board instead.
        Category(R.string.settings_category_emoji),
        Toggle(
            store = boolPref("pref_emoji_dynamic_search", false),
            title = R.string.pref_emoji_search_title,
            icon = drawableIcon(R.drawable.ic_settings_emoji_search),
            summary = RowSummary.Res(R.string.pref_emoji_search_vkb_summary),
            modifier = Modifier.settingsSearchAnchor("pref_emoji_dynamic_search"),
        ),
        Toggle(
            store = boolPref("pref_emoji_search_replace_text", true),
            title = R.string.pref_emoji_search_replace_text_title,
            icon = drawableIcon(R.drawable.ic_settings_emoji_replace_text),
            summary = RowSummary.OnOff(
                R.string.pref_emoji_search_replace_text_summary_on,
                R.string.pref_emoji_search_replace_text_summary_off,
            ),
            enabled = { it.bool("pref_emoji_dynamic_search") },
            modifier = Modifier.settingsSearchAnchor("pref_emoji_search_replace_text"),
        ),
        // The two links to other screens close the page, each under its own header, so Behavior
        // holds settings only (2026-11-11). This group is the slideboard's one home.
        Category(R.string.settings_category_slideboard),
        Nav(
            title = R.string.settings_slideboard_settings_title,
            icon = drawableIcon(R.drawable.ic_settings_slideboard),
            summary = R.string.settings_slideboard_hub_summary,
            onClick = { onNavigateToSlideboard() },
        ),
        Category(R.string.settings_category_feedback),
        Nav(
            title = R.string.settings_touch_feedback_title,
            icon = drawableIcon(R.drawable.ic_settings_touch_feedback),
            summary = R.string.settings_touch_feedback_summary,
            onClick = { onNavigateToKeyPressFeedback() },
        ),
    ))
}
