package dev.bbkb.ime.core.settings.screens

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.Category
import dev.bbkb.ime.core.settings.ui.Choice
import dev.bbkb.ime.core.settings.ui.ChoiceOption
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.RowSummary
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Toggle
import dev.bbkb.ime.core.settings.ui.boolPref
import dev.bbkb.ime.core.settings.ui.drawableIcon
import dev.bbkb.ime.core.settings.ui.stringPref
import dev.bbkb.ime.keyboard.inputboard.clipboard.ClipboardHistoryManager
import dev.bbkb.ime.keyboard.inputboard.clipboard.ClipboardPrefs

/**
 * Input > Auxiliary input: the input menu, clipboard and shake actions. The clipboard keys are
 * [ClipboardPrefs]'s, written as literals because the search index anchors on them.
 */
@Composable
fun AuxiliaryInputScreen(
    onNavigateToCustomizeMenu: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    SettingsScreenHost(R.string.settings_auxiliary_input_title, onNavigateBack, listOf(
        Category(R.string.settings_category_input_menu),
        Toggle(
            store = boolPref("pref_uim_enabled", true),
            title = R.string.settings_uim_enable_title,
            icon = drawableIcon(R.drawable.ic_settings_uim),
            summary = RowSummary.OnOff(
                R.string.settings_uim_enable_summary_on,
                R.string.settings_uim_enable_summary_off,
            ),
            modifier = Modifier.settingsSearchAnchor("pref_uim_enabled"),
        ),
        // Enabled while the menu's switch is off: a key can still raise the menu then.
        Nav(
            title = R.string.settings_customize_menu_title,
            icon = drawableIcon(R.drawable.ic_settings_uim_customize),
            summary = R.string.settings_customize_menu_summary,
            onClick = { onNavigateToCustomizeMenu() },
        ),

        Category(R.string.settings_uim_toggle_clipboard),
        // Off also deletes kept clips, pinned ones included.
        Toggle(
            store = boolPref("pref_clipboard_history_enabled", ClipboardPrefs.DEFAULT_HISTORY_ENABLED),
            title = R.string.clipboard_history_title,
            icon = drawableIcon(R.drawable.ic_settings_clipboard_history),
            summary = RowSummary.Res(R.string.clipboard_history_summary),
            modifier = Modifier.settingsSearchAnchor("pref_clipboard_history_enabled"),
            onWrite = { context, enabled ->
                if (!enabled) ClipboardHistoryManager.clearHistory(context)
            },
        ),
        Choice(
            store = stringPref("pref_clipboard_retention", ClipboardPrefs.DEFAULT_RETENTION),
            title = R.string.clipboard_retention_title,
            icon = drawableIcon(R.drawable.ic_settings_clip_retention),
            options = ::retentionOptions,
            // An unknown stored value reads as the default, one hour, as ClipboardPrefs does.
            fallbackIndex = 0,
            enabled = { it.bool("pref_clipboard_history_enabled") },
            modifier = Modifier.settingsSearchAnchor("pref_clipboard_retention"),
        ),
        Toggle(
            store = boolPref("pref_clipboard_link_previews", ClipboardPrefs.DEFAULT_LINK_PREVIEWS),
            title = R.string.clipboard_link_previews_title,
            icon = drawableIcon(R.drawable.ic_settings_link_previews),
            summary = RowSummary.Res(R.string.clipboard_link_previews_summary),
            enabled = { it.bool("pref_clipboard_history_enabled") },
            modifier = Modifier.settingsSearchAnchor("pref_clipboard_link_previews"),
        ),

        Category(R.string.settings_shake_title),
        // Literal anchors: the search index test finds them by text.
        shakeAction("shake_x_action", R.string.settings_shake_x_axis_title, R.drawable.ic_settings_shake_horizontal,
            Modifier.settingsSearchAnchor("shake_x_action")),
        shakeAction("shake_y_action", R.string.settings_shake_y_axis_title, R.drawable.ic_settings_shake_vertical,
            Modifier.settingsSearchAnchor("shake_y_action")),
        shakeAction("shake_z_action", R.string.settings_shake_z_axis_title, R.drawable.ic_settings_shake_diagonal,
            Modifier.settingsSearchAnchor("shake_z_action")),
        shakeAction("shake_fallback_action", R.string.settings_shake_fallback_title, R.drawable.ic_settings_shake_other,
            Modifier.settingsSearchAnchor("shake_fallback_action")),
    ))
}

/** One shake's action. All four rows offer the same list and fall back to its first entry. */
private fun shakeAction(key: String, titleRes: Int, @DrawableRes iconRes: Int, anchor: Modifier) = Choice(
    store = stringPref(key, "0"),
    title = titleRes,
    icon = drawableIcon(iconRes),
    options = ::shakeActionOptions,
    modifier = anchor,
)

/** R.array.prefs_shake_action_options / _values, kept parallel there. */
private fun shakeActionOptions(context: Context): List<ChoiceOption> {
    val labels = context.resources.getStringArray(R.array.prefs_shake_action_options)
    val values = context.resources.getStringArray(R.array.prefs_shake_action_values)
    return values.mapIndexed { index, value ->
        ChoiceOption(value, labels.getOrElse(index) { value })
    }
}

private fun retentionOptions(context: Context): List<ChoiceOption> = listOf(
    ChoiceOption(ClipboardPrefs.RETENTION_1H, context.getString(R.string.clipboard_retention_1h)),
    ChoiceOption(ClipboardPrefs.RETENTION_24H, context.getString(R.string.clipboard_retention_24h)),
    ChoiceOption(ClipboardPrefs.RETENTION_7D, context.getString(R.string.clipboard_retention_7d)),
    ChoiceOption(ClipboardPrefs.RETENTION_NEVER, context.getString(R.string.clipboard_retention_never)),
)
