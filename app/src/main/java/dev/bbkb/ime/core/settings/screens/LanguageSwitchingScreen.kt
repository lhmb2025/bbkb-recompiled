package dev.bbkb.ime.core.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.RowSummary
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Toggle
import dev.bbkb.ime.core.settings.ui.boolPref
import dev.bbkb.ime.R

private const val SHOW_SWITCH_KEY = "pref_show_language_switch_key"

/**
 * Language Switching Settings Screen (Subpage of Languages & Input)
 * Configure language switching behavior and options
 */
@Composable
fun LanguageSwitchingScreen(
    onNavigateBack: () -> Unit
) {
    SettingsScreenHost(R.string.settings_language_switching_title, onNavigateBack, listOf(
        Toggle(
            store = boolPref(SHOW_SWITCH_KEY, true),
            title = R.string.settings_lang_switch_key_title,
            summary = RowSummary.Res(R.string.settings_lang_switch_key_summary),
            modifier = Modifier.settingsSearchAnchor("pref_show_language_switch_key"),
        ),
        Toggle(
            store = boolPref("pref_include_other_imes_in_language_switch_list", false),
            title = R.string.settings_lang_include_other_title,
            summary = RowSummary.Res(R.string.settings_lang_include_other_summary),
            enabled = { it.bool(SHOW_SWITCH_KEY) },
            modifier = Modifier.settingsSearchAnchor("pref_include_other_imes_in_language_switch_list"),
        ),
        Toggle(
            store = boolPref("pref_language_quick_switch_key", false),
            title = R.string.settings_lang_quick_switch_title,
            summary = RowSummary.Res(R.string.settings_lang_quick_switch_summary),
            modifier = Modifier.settingsSearchAnchor("pref_language_quick_switch_key"),
        ),
        Toggle(
            store = boolPref("pref_spacebar_language_switching", true),
            title = R.string.settings_lang_spacebar_switch_title,
            summary = RowSummary.Res(R.string.settings_lang_spacebar_switch_summary),
            modifier = Modifier.settingsSearchAnchor("pref_spacebar_language_switching"),
        ),
    ))
}
