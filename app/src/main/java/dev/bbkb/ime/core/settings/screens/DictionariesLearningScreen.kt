package dev.bbkb.ime.core.settings.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ModelTraining
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.ReadMore
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.RowSummary
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Toggle
import dev.bbkb.ime.core.settings.ui.asRowIcon
import dev.bbkb.ime.core.settings.ui.drawableIcon
import dev.bbkb.ime.core.settings.ui.boolPref
import dev.bbkb.ime.R

/**
 * Dictionaries & Learning Settings (Subpage 3 of Correction & Learning)
 * User-managed vocabulary and learning controls
 *
 * Provides access to three dictionary management screens:
 * 1. User Dictionary - Personal words (BASL-backed)
 * 2. Text Shortcuts - Word substitutions with macro support
 * 3. Learned Words - Nuance DLM words with promote feature
 */
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
            icon = Icons.Outlined.ModelTraining.asRowIcon(),
            modifier = Modifier.settingsSearchAnchor("dynamic_learning"),
        ),
        // SettingsValues.showUiToAcceptTypedWord: after backspace undoes an auto-correction, the
        // word you typed is highlighted and a small button beside it adds it to the user
        // dictionary (RecorrectionController.revertAutoCorrection + TextDecorator). It lived on
        // the Debug screen as "Typed word accept button", described as a suggestion-bar button,
        // which it never was.
        Toggle(
            store = boolPref("pref_show_ui_to_accept_typed_word", true),
            title = R.string.settings_dict_add_reverted_words_title,
            summary = RowSummary.Res(R.string.settings_dict_add_reverted_words_summary),
            icon = Icons.Outlined.PlaylistAdd.asRowIcon(),
            modifier = Modifier.settingsSearchAnchor("pref_show_ui_to_accept_typed_word"),
        ),
        Nav(
            title = R.string.settings_dict_user_dictionary_title,
            summary = R.string.settings_dict_user_dictionary_summary,
            icon = drawableIcon(R.drawable.ic_settings_user_dictionary),
            onClick = { onNavigateToUserDictionary() },
        ),
        Nav(
            title = R.string.settings_dict_text_shortcuts_title,
            summary = R.string.settings_dict_text_shortcuts_summary,
            icon = Icons.Outlined.ReadMore.asRowIcon(),
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
