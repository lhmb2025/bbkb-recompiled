package dev.bbkb.ime.core.settings.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.ReadMore
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.RowSummary
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Toggle
import dev.bbkb.ime.core.settings.ui.asRowIcon
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
            icon = Icons.Default.ModelTraining.asRowIcon(),
            modifier = Modifier.settingsSearchAnchor("dynamic_learning"),
        ),
        Nav(
            title = R.string.settings_dict_user_dictionary_title,
            summary = R.string.settings_dict_user_dictionary_summary,
            icon = Icons.Default.Book.asRowIcon(),
            onClick = { onNavigateToUserDictionary() },
        ),
        Nav(
            title = R.string.settings_dict_text_shortcuts_title,
            summary = R.string.settings_dict_text_shortcuts_summary,
            icon = Icons.Default.ReadMore.asRowIcon(),
            onClick = { onNavigateToTextShortcuts() },
        ),
        Nav(
            title = R.string.settings_dict_learned_words_title,
            summary = R.string.settings_dict_learned_words_summary,
            icon = Icons.Default.HistoryEdu.asRowIcon(),
            onClick = { onNavigateToLearnedWords() },
        ),
    ))
}
