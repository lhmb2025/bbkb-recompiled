package dev.bbkb.ime.core.settings.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.runtime.Composable
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.asRowIcon
import dev.bbkb.ime.core.settings.ui.drawableIcon
import dev.bbkb.ime.R

/**
 * Languages Settings Screen (per docs/archived/2026-01_compose-settings-and-dictionaries/2026-01_settings-compose_history.md)
 * Contains only language-related settings:
 * - Multi-language keyboards
 * - Language switching
 * - Language packs
 */
@Composable
fun LanguagesInputScreen(
    onNavigateToLanguageSwitching: () -> Unit,
    onNavigateToMultiLanguageKeyboards: () -> Unit,
    onNavigateToLanguagePacks: () -> Unit,
    onNavigateToLanguagesHub: () -> Unit,
    onNavigateBack: () -> Unit
) {
    SettingsScreenHost(R.string.settings_languages_title, onNavigateBack, listOf(
        // Preview of the consolidated screen, alongside the three it is meant to replace.
        Nav(
            title = R.string.settings_languages_title,
            summary = R.string.languages_hub_summary,
            icon = Icons.Default.Language.asRowIcon(),
            onClick = { onNavigateToLanguagesHub() },
        ),
        Nav(
            title = R.string.settings_multi_language_keyboards_title,
            summary = R.string.settings_multi_language_keyboards_summary,
            icon = drawableIcon(R.drawable.ic_settings_multi_language_keyboard),
            onClick = { onNavigateToMultiLanguageKeyboards() },
        ),
        Nav(
            title = R.string.settings_language_switching_title,
            summary = R.string.settings_language_switching_summary,
            icon = Icons.Default.SyncAlt.asRowIcon(),
            onClick = { onNavigateToLanguageSwitching() },
        ),
        Nav(
            title = R.string.settings_language_packs_title,
            summary = R.string.settings_language_packs_summary,
            icon = Icons.Default.Archive.asRowIcon(),
            onClick = { onNavigateToLanguagePacks() },
        ),
    ))
}
