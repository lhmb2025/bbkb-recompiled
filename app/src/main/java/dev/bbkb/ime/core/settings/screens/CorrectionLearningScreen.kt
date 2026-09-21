package dev.bbkb.ime.core.settings.screens

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.runtime.Composable
import dev.bbkb.ime.core.settings.SpellCheckerComposeActivity
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.asRowIcon
import dev.bbkb.ime.core.settings.ui.drawableIcon
import dev.bbkb.ime.R

/**
 * Suggestion and Correction hub screen (primary settings category)
 * Links to: Suggestion, Correction, Learning, Spell checking
 */
@Composable
fun CorrectionLearningScreen(
    onNavigateToAutoCorrection: () -> Unit,
    onNavigateToPredictions: () -> Unit,
    onNavigateToDictionaries: () -> Unit,
    onNavigateBack: () -> Unit
) {
    SettingsScreenHost(R.string.settings_suggestion_correction_title, onNavigateBack, listOf(
        Nav(
            title = R.string.settings_suggestion_title,
            summary = R.string.settings_suggestion_summary,
            icon = drawableIcon(R.drawable.ic_settings_prediction),
            onClick = { onNavigateToPredictions() },
        ),
        Nav(
            title = R.string.settings_correction_title,
            summary = R.string.settings_correction_summary,
            icon = Icons.Default.AutoFixHigh.asRowIcon(),
            onClick = { onNavigateToAutoCorrection() },
        ),
        Nav(
            title = R.string.settings_dictionary_learning_title,
            summary = R.string.settings_dictionary_learning_summary,
            icon = drawableIcon(R.drawable.ic_settings_learning),
            onClick = { onNavigateToDictionaries() },
        ),
        // Spell checking — launches its own activity
        Nav(
            title = R.string.settings_spell_checker_title,
            summary = R.string.settings_spell_checker_summary,
            icon = Icons.Default.Spellcheck.asRowIcon(),
            onClick = { context ->
                context.startActivity(Intent(context, SpellCheckerComposeActivity::class.java))
            },
        ),
    ))
}
