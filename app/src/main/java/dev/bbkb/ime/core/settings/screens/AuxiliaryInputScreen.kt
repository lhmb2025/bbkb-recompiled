package dev.bbkb.ime.core.settings.screens

import android.content.Context
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
 * Input > Auxiliary input: the tools around typing itself, which the unified input menu
 * launches. The menu rows used to sit under On-screen keyboard, the emoji rows under
 * Suggestions, voice input was its own entry on the Input hub, and the clipboard rows had a
 * screen of their own (its "Clear history" action went with it: switching history off wipes
 * the clips anyway).
 *
 * "Replace text with emoji" greys out with dynamic search off rather than disappearing, so the
 * row count is fixed. The clipboard keys are [ClipboardPrefs]'s, written out as literals because
 * the search index anchors on them.
 */
@Composable
fun AuxiliaryInputScreen(
    onNavigateToCustomizeMenu: () -> Unit,
    onNavigateToVoiceInput: () -> Unit,
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
        // There is nothing to customize while the menu itself is off.
        Nav(
            title = R.string.settings_customize_menu_title,
            icon = drawableIcon(R.drawable.ic_settings_uim_customize),
            summary = R.string.settings_customize_menu_summary,
            enabled = { it.bool("pref_uim_enabled") },
            onClick = { onNavigateToCustomizeMenu() },
        ),

        Category(R.string.settings_category_voice),
        Nav(
            title = R.string.settings_voice_input_title,
            icon = drawableIcon(R.drawable.ic_settings_voice_input),
            summary = R.string.settings_voice_input_summary,
            onClick = { onNavigateToVoiceInput() },
        ),

        Category(R.string.settings_category_emoji),
        Toggle(
            store = boolPref("pref_emoji_dynamic_search", false),
            title = R.string.pref_emoji_dynamic_search_title,
            icon = drawableIcon(R.drawable.ic_settings_emoji_search),
            summary = RowSummary.OnOff(
                R.string.pref_emoji_dynamic_search_summary_on,
                R.string.pref_emoji_dynamic_search_summary_off,
            ),
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

        Category(R.string.settings_uim_toggle_clipboard),
        // Off stops capturing and deletes what was kept, pinned clips included: a history the
        // user switched off must not stay on the device.
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
    ))
}

private fun retentionOptions(context: Context): List<ChoiceOption> = listOf(
    ChoiceOption(ClipboardPrefs.RETENTION_1H, context.getString(R.string.clipboard_retention_1h)),
    ChoiceOption(ClipboardPrefs.RETENTION_24H, context.getString(R.string.clipboard_retention_24h)),
    ChoiceOption(ClipboardPrefs.RETENTION_7D, context.getString(R.string.clipboard_retention_7d)),
    ChoiceOption(ClipboardPrefs.RETENTION_NEVER, context.getString(R.string.clipboard_retention_never)),
)
