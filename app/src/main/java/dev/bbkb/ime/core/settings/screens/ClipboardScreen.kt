package dev.bbkb.ime.core.settings.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.BbkbDialogs
import dev.bbkb.ime.core.settings.ui.Choice
import dev.bbkb.ime.core.settings.ui.ChoiceOption
import dev.bbkb.ime.core.settings.ui.RowSummary
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Simple
import dev.bbkb.ime.core.settings.ui.Toggle
import dev.bbkb.ime.core.settings.ui.boolPref
import dev.bbkb.ime.core.settings.ui.stringPref
import dev.bbkb.ime.keyboard.inputboard.clipboard.ClipboardHistoryManager
import dev.bbkb.ime.keyboard.inputboard.clipboard.ClipboardPrefs

/**
 * Clipboard settings: whether copied text is kept, for how long, whether copied links get a
 * preview, and wiping what is kept. Reached from On-Screen Keyboard, beside the unified input
 * menu rows that open the clipboard board.
 *
 * The keys are [ClipboardPrefs]'s, written out here as literals because the search index anchors
 * on them (see `SettingsSearchIndexTest`).
 */
@Composable
fun ClipboardScreen(onNavigateBack: () -> Unit) {
    SettingsScreenHost(R.string.settings_uim_toggle_clipboard, onNavigateBack, listOf(
        // Off stops capturing and deletes what was kept, pinned clips included: a history the
        // user switched off must not stay on the device.
        Toggle(
            store = boolPref("pref_clipboard_history_enabled", ClipboardPrefs.DEFAULT_HISTORY_ENABLED),
            title = R.string.clipboard_history_title,
            summary = RowSummary.Res(R.string.clipboard_history_summary),
            modifier = Modifier.settingsSearchAnchor("pref_clipboard_history_enabled"),
            onWrite = { context, enabled ->
                if (!enabled) ClipboardHistoryManager.clearHistory(context)
            },
        ),
        Choice(
            store = stringPref("pref_clipboard_retention", ClipboardPrefs.DEFAULT_RETENTION),
            title = R.string.clipboard_retention_title,
            options = ::retentionOptions,
            // An unknown stored value reads as the default, one hour, as ClipboardPrefs does.
            fallbackIndex = 0,
            enabled = { it.bool("pref_clipboard_history_enabled") },
            modifier = Modifier.settingsSearchAnchor("pref_clipboard_retention"),
        ),
        Toggle(
            store = boolPref("pref_clipboard_link_previews", ClipboardPrefs.DEFAULT_LINK_PREVIEWS),
            title = R.string.clipboard_link_previews_title,
            summary = RowSummary.Res(R.string.clipboard_link_previews_summary),
            enabled = { it.bool("pref_clipboard_history_enabled") },
            modifier = Modifier.settingsSearchAnchor("pref_clipboard_link_previews"),
        ),
        Simple(
            title = R.string.clipboard_clear_history_title,
            summary = R.string.clipboard_clear_history_summary,
            modifier = Modifier.settingsSearchAnchor("clipboard_clear_history"),
            onClick = ::showClearHistoryDialog,
        ),
    ))
}

private fun retentionOptions(context: Context): List<ChoiceOption> = listOf(
    ChoiceOption(ClipboardPrefs.RETENTION_1H, context.getString(R.string.clipboard_retention_1h)),
    ChoiceOption(ClipboardPrefs.RETENTION_24H, context.getString(R.string.clipboard_retention_24h)),
    ChoiceOption(ClipboardPrefs.RETENTION_7D, context.getString(R.string.clipboard_retention_7d)),
    ChoiceOption(ClipboardPrefs.RETENTION_NEVER, context.getString(R.string.clipboard_retention_never)),
)

private fun showClearHistoryDialog(context: Context) {
    BbkbDialogs.builder(context)
        .setTitle(context.getString(R.string.clipboard_clear_history_dialog_title))
        .setMessage(context.getString(R.string.clipboard_clear_history_summary))
        .setPositiveButton(R.string.clipboard_clear_history_title) { _, _ ->
            ClipboardHistoryManager.clearHistory(context)
        }
        .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.cancel() }
        .show()
}
