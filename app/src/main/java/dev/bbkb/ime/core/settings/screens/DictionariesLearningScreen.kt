package dev.bbkb.ime.core.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.RowSummary
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Toggle
import dev.bbkb.ime.core.settings.ui.drawableIcon
import dev.bbkb.ime.core.settings.ui.groupBreak
import dev.bbkb.ime.core.settings.ui.boolPref
import dev.bbkb.ime.R

/** Links to User dictionary (BASL), Text shortcuts and Learned words (Nuance DLM). */
@Composable
fun DictionariesLearningScreen(
    onNavigateToUserDictionary: () -> Unit,
    onNavigateToTextShortcuts: () -> Unit,
    onNavigateToLearnedWords: () -> Unit,
    onNavigateBack: () -> Unit
) {
    SettingsScreenHost(R.string.settings_dictionaries_learning_title, onNavigateBack, listOf(
        Toggle(
            store = boolPref("dynamic_learning", true),
            title = R.string.settings_dict_dynamic_learning_title,
            summary = RowSummary.Res(R.string.settings_dict_dynamic_learning_summary),
            icon = drawableIcon(R.drawable.ic_settings_m_model_training),
            modifier = Modifier.settingsSearchAnchor("dynamic_learning"),
        ),
        groupBreak(),
        // SettingsValues.showUiToAcceptTypedWord: after backspace undoes an auto-correction, a
        // button beside the typed word adds it to the user dictionary (TextDecorator).
        Toggle(
            store = boolPref("pref_show_ui_to_accept_typed_word", true),
            title = R.string.settings_dict_add_reverted_words_title,
            summary = RowSummary.Res(R.string.settings_dict_add_reverted_words_summary),
            icon = drawableIcon(R.drawable.ic_settings_m_playlist_add),
            modifier = Modifier.settingsSearchAnchor("pref_show_ui_to_accept_typed_word"),
        ),
        groupBreak(),
        Nav(
            title = R.string.settings_dict_user_dictionary_title,
            summary = R.string.settings_dict_user_dictionary_summary,
            icon = drawableIcon(R.drawable.ic_settings_user_dictionary),
            onClick = { onNavigateToUserDictionary() },
        ),
        Nav(
            title = R.string.settings_dict_text_shortcuts_title,
            summary = R.string.settings_dict_text_shortcuts_summary,
            icon = drawableIcon(R.drawable.ic_settings_m_read_more),
            onClick = { onNavigateToTextShortcuts() },
        ),
        Nav(
            title = R.string.settings_dict_learned_words_title,
            summary = R.string.settings_dict_learned_words_summary,
            icon = drawableIcon(R.drawable.ic_settings_learned_words),
            onClick = { onNavigateToLearnedWords() },
        ),
    ))
}
